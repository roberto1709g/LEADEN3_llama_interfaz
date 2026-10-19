package com.leaden2.app;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.ActivityNotFoundException;
import android.net.Uri;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.provider.Settings;
import android.text.TextUtils;
import android.content.ComponentName;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class VoiceAssistantService extends Service implements TextToSpeech.OnInitListener {
    public static final String ACTION_PREPARE = "com.leaden2.app.voice.PREPARE";
    public static final String ACTION_LISTEN = "com.leaden2.app.voice.LISTEN";
    private static final String TAG = "LEADEN2Voice";
    private static final String CHANNEL_ID = "leaden2_voice_service";
    private static final int NOTIFICATION_ID = 2202;
    private static final String PREFS = "leaden2_local_data";
    private static final String WEBHOOK_URL = "https://n8n.serviapps.duckdns.org/webhook/asistente-leaden2";

    private SpeechRecognizer speechRecognizer;
    private TextToSpeech textToSpeech;
    private boolean ttsReady = false;
    private boolean listening = false;
    private boolean ttsBusy = false;
    private String pendingSpeech;
    private boolean awaitingCallConfirmation = false;
    private String pendingCallContactName;
    private String pendingCallPhone;
    private String pendingWhatsAppContactName;
    private String pendingWhatsAppPhone;
    private boolean pendingWhatsAppAutoCall = false;
    private final ExecutorService networkExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        textToSpeech = new TextToSpeech(this, this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        promoteToForeground();
        String action = intent == null ? ACTION_PREPARE : intent.getAction();
        if (ACTION_LISTEN.equals(action)) startListening();
        return START_STICKY;
    }

    private void promoteToForeground() {
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("LEADEN2 está activo")
            .setContentText("Servicio de voz preparado para las gafas")
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void startListening() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            speak("Para usar el asistente, debes permitir el acceso al micrófono.");
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            speak("El reconocimiento de voz no está disponible en este teléfono.");
            return;
        }
        if (listening) {
            try { speechRecognizer.cancel(); } catch (Exception ignored) { }
            listening = false;
        }
        if (speechRecognizer != null) {
            try { speechRecognizer.destroy(); } catch (Exception ignored) { }
            speechRecognizer = null;
        }
        try {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
            speechRecognizer.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle params) { Log.i(TAG, "Reconocimiento preparado."); }
                @Override public void onBeginningOfSpeech() { Log.i(TAG, "Usuario comenzó a hablar."); }
                @Override public void onRmsChanged(float rmsdB) { }
                @Override public void onBufferReceived(byte[] buffer) { }
                @Override public void onEndOfSpeech() { listening = false; }
                @Override public void onError(int error) {
                    listening = false;
                    Log.w(TAG, "Error de reconocimiento: " + error);
                    if (awaitingCallConfirmation) {
                        clearPendingCallConfirmation();
                        speak("No pude escuchar tu confirmación. He cancelado la operación por seguridad.");
                    } else {
                        speak(errorMessage(error));
                    }
                }
                @Override public void onResults(Bundle results) {
                    listening = false;
                    ArrayList<String> matches = results == null ? null : results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    String recognized = matches == null || matches.isEmpty() ? "" : matches.get(0).trim();
                    if (recognized.isEmpty()) speak("No logré entender la consulta. Inténtalo otra vez.");
                    else {
                        Log.i(TAG, "Consulta de voz reconocida; caracteres=" + recognized.length());
                        if (awaitingCallConfirmation) {
                            processCallConfirmation(recognized);
                            return;
                        }
                        ActionType action = classify(recognized);
                        if (action == ActionType.CALL) {
                            // Procesamiento local: las solicitudes CALL nunca se envían a n8n.
                            Log.i(TAG, "Solicitud CALL interceptada localmente; no se enviará al webhook.");
                            processLocalCallRequest(recognized);
                        } else {
                            sendToWebhook(recognized);
                        }
                    }
                }
                @Override public void onPartialResults(Bundle partialResults) { }
                @Override public void onEvent(int eventType, Bundle params) { }
            });
            Intent recognitionIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            recognitionIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            recognitionIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-EC");
            recognitionIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "es-EC");
            recognitionIntent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
            recognitionIntent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
            listening = true;
            speechRecognizer.startListening(recognitionIntent);
            Log.i(TAG, "Escucha iniciada.");
        } catch (Exception e) {
            listening = false;
            Log.e(TAG, "No se pudo iniciar el reconocimiento de voz.", e);
            speak("No pude iniciar el reconocimiento de voz. Comprueba el permiso del micrófono.");
        }
    }

    private void sendToWebhook(String recognizedText) {
        networkExecutor.execute(() -> {
            HttpURLConnection connection = null;
            try {
                SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
                JSONObject profile = new JSONObject(prefs.getString("profile", "{}"));
                if (profile.optString("nombre").trim().isEmpty()
                    || profile.optString("cedula").trim().isEmpty()
                    || profile.optString("celular").trim().isEmpty()
                    || profile.optString("correo").trim().isEmpty()) {
                    speak("Completa y guarda los datos del usuario en LEADEN2 antes de hacer una consulta.");
                    return;
                }

                JSONObject payload = new JSONObject();
                payload.put("nombre", profile.optString("nombre"));
                payload.put("cedula", profile.optString("cedula"));
                payload.put("celular", profile.optString("celular"));
                payload.put("correo", profile.optString("correo"));
                payload.put("text", recognizedText);

                ActionType action = classify(recognizedText);
                if (action == ActionType.ANALYSIS) {
                    payload.put("analisis", "analisis");
                } else if (action == ActionType.MESSAGE) {
                    payload.put("contactos", new JSONArray(prefs.getString("contacts", "[]")));
                }

                URL url = new URL(WEBHOOK_URL);
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(30000);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                connection.setRequestProperty("Accept", "application/json");
                byte[] body = payload.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(body.length);
                try (OutputStream output = connection.getOutputStream()) { output.write(body); }
                int status = connection.getResponseCode();
                InputStream stream = status >= 200 && status < 400 ? connection.getInputStream() : connection.getErrorStream();
                String response = readAll(stream);
                if (status < 200 || status >= 300) {
                    Log.e(TAG, "Webhook devolvió HTTP " + status);
                    speak("El servidor del asistente devolvió un error. Inténtalo más tarde.");
                    return;
                }
                String outputText = extractOutput(response);
                if (outputText.isEmpty()) {
                    Log.w(TAG, "La respuesta no contiene el campo output.");
                    speak("Recibí una respuesta del servidor, pero no encontré el texto para leer.");
                } else {
                    Log.i(TAG, "Respuesta del webhook recibida correctamente.");
                    speak(outputText);
                }
            } catch (Exception e) {
                Log.e(TAG, "Falló la comunicación con el webhook LEADEN2.", e);
                speak("No pude comunicarme con el asistente. Comprueba la conexión a internet.");
            } finally {
                if (connection != null) connection.disconnect();
            }
        });
    }


    /** Busca el contacto solicitado en la agenda local de LEADEN2. No inicia llamadas. */
    private void processLocalCallRequest(String recognizedText) {
        String requestedName = extractRequestedContactName(recognizedText);
        if (requestedName.isEmpty()) {
            speak("No entendí el nombre del contacto. Di, por ejemplo, llamar a Roberto.");
            return;
        }

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        JSONArray contacts;
        try {
            contacts = new JSONArray(prefs.getString("contacts", "[]"));
        } catch (JSONException e) {
            Log.e(TAG, "La agenda local no contiene un JSON válido.", e);
            speak("No pude leer la agenda local de contactos.");
            return;
        }

        ArrayList<JSONObject> exactMatches = new ArrayList<>();
        ArrayList<JSONObject> partialMatches = new ArrayList<>();
        String requestedNormalized = normalizeWords(requestedName);
        for (int i = 0; i < contacts.length(); i++) {
            JSONObject contact = contacts.optJSONObject(i);
            if (contact == null) continue;
            String contactName = contact.optString("nombre", "").trim();
            if (contactName.isEmpty()) continue;
            String contactNormalized = normalizeWords(contactName);
            if (contactNormalized.equals(requestedNormalized)) {
                exactMatches.add(contact);
            } else if (contactNormalized.startsWith(requestedNormalized + " ")
                    || requestedNormalized.startsWith(contactNormalized + " ")) {
                partialMatches.add(contact);
            }
        }

        ArrayList<JSONObject> matches = exactMatches.isEmpty() ? partialMatches : exactMatches;
        if (matches.isEmpty()) {
            Log.i(TAG, "Contacto no encontrado en agenda local: " + requestedNormalized);
            speak("No encontré a " + requestedName + " en la agenda de LEADEN2.");
            return;
        }
        if (matches.size() > 1) {
            StringBuilder names = new StringBuilder();
            for (JSONObject match : matches) {
                if (names.length() > 0) names.append(", ");
                names.append(match.optString("nombre", "contacto"));
            }
            Log.i(TAG, "Búsqueda de contacto ambigua; coincidencias=" + matches.size());
            speak("Encontré varios contactos que coinciden: " + names + ". Di el nombre completo.");
            return;
        }

        JSONObject contact = matches.get(0);
        String contactName = contact.optString("nombre", requestedName).trim();
        String normalizedPhone = normalizeEcuadorMobile(contact.optString("telefono", ""));
        if (normalizedPhone == null) {
            Log.w(TAG, "El contacto encontrado tiene un teléfono móvil ecuatoriano inválido: " + contactName);
            speak("Encontré a " + contactName + ", pero su número no es un móvil ecuatoriano válido. Corrígelo en la agenda.");
            return;
        }

        Log.i(TAG, "Contacto local encontrado; nombre=" + contactName + ", número válido=" + normalizedPhone);
        pendingCallContactName = contactName;
        pendingCallPhone = normalizedPhone;
        awaitingCallConfirmation = true;
        speak("He encontrado a " + contactName + ". ¿Confirmas que deseas llamar a este contacto? Responde sí para confirmar o no para cancelar.");
    }

    /** Procesa la confirmación localmente y abre el chat de WhatsApp; la llamada requiere tocar su icono. */
    private void processCallConfirmation(String recognizedText) {
        String response = normalizeWords(recognizedText);
        if (response.equals("si") || response.equals("si confirmo") || response.equals("confirmo")
                || response.equals("correcto") || response.equals("adelante") || response.equals("de acuerdo")) {
            String contactName = pendingCallContactName == null ? "el contacto" : pendingCallContactName;
            String phone = pendingCallPhone == null ? "" : pendingCallPhone;
            clearPendingCallConfirmation();
            if (phone.isEmpty()) {
                Log.w(TAG, "Confirmación recibida sin número pendiente; no se abrirá WhatsApp.");
                speak("No tengo un número validado para este contacto. No abrí WhatsApp.");
                return;
            }
            pendingWhatsAppContactName = contactName;
            pendingWhatsAppPhone = phone;
            pendingWhatsAppAutoCall = isWhatsAppCallAccessibilityEnabled();
            if (pendingWhatsAppAutoCall) {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putBoolean("wa_auto_call_armed", true)
                    .putString("wa_auto_call_name", contactName)
                    .putString("wa_auto_call_phone", phone)
                    .putLong("wa_auto_call_armed_at", System.currentTimeMillis())
                    .apply();
                Log.i(TAG, "Confirmación afirmativa recibida para " + contactName + "; automatización de llamada armada para el número validado.");
                speak("Confirmado. Intentaré iniciar automáticamente la llamada de voz de WhatsApp a " + contactName + ".");
            } else {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("wa_auto_call_armed", false).apply();
                Log.i(TAG, "Servicio de accesibilidad de llamadas no habilitado; se abrirá únicamente el chat.");
                speak("Confirmado. Para intentar iniciar la llamada automáticamente, primero activa el servicio de accesibilidad de LEADEN2 en los ajustes de Android. Abriré el chat de " + contactName + ", pero esta vez no iniciaré la llamada.");
            }
            return;
        }
        if (response.equals("no") || response.equals("no confirmo") || response.equals("cancelar")
                || response.equals("cancela") || response.equals("cancelalo") || response.equals("mejor no")) {
            String contactName = pendingCallContactName == null ? "la llamada" : "la llamada a " + pendingCallContactName;
            clearPendingCallConfirmation();
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("wa_auto_call_armed", false).apply();
            Log.i(TAG, "Confirmación de llamada cancelada por el usuario.");
            speak("De acuerdo. He cancelado " + contactName + ".");
            return;
        }
        clearPendingCallConfirmation();
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("wa_auto_call_armed", false).apply();
        Log.i(TAG, "Respuesta de confirmación no reconocida; operación cancelada por seguridad.");
        speak("No entendí la respuesta. He cancelado la operación por seguridad. Si deseas intentarlo, vuelve a decir llamar y el nombre del contacto.");
    }

    /** Comprueba si el usuario habilitó explícitamente nuestro servicio de accesibilidad. */
    private boolean isWhatsAppCallAccessibilityEnabled() {
        try {
            String enabled = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            ComponentName component = new ComponentName(this, WhatsAppCallAccessibilityService.class);
            return !TextUtils.isEmpty(enabled) && enabled.toLowerCase(Locale.ROOT)
                .contains(component.flattenToString().toLowerCase(Locale.ROOT));
        } catch (Exception e) {
            Log.w(TAG, "No se pudo verificar el servicio de accesibilidad.", e);
            return false;
        }
    }

    /** Abre el chat del número validado; si la accesibilidad está habilitada, esta intentará pulsar llamada de voz. */
    private void openPendingWhatsAppChat() {
        String contactName = pendingWhatsAppContactName == null ? "el contacto" : pendingWhatsAppContactName;
        String phone = pendingWhatsAppPhone == null ? "" : pendingWhatsAppPhone;
        pendingWhatsAppContactName = null;
        pendingWhatsAppPhone = null;
        if (phone.isEmpty()) {
            speak("No tengo un número válido para abrir WhatsApp.");
            return;
        }
        String digits = phone.replaceAll("\\D", "");
        Uri uri = Uri.parse("https://wa.me/" + digits);
        String[] packages = {"com.whatsapp", "com.whatsapp.w4b"};
        for (String packageName : packages) {
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW, uri);
                intent.setPackage(packageName);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                Log.i(TAG, "Chat de WhatsApp abierto para " + contactName + " mediante " + packageName + ". Automatización armada=" + pendingWhatsAppAutoCall + ".");
                return;
            } catch (ActivityNotFoundException e) {
                Log.i(TAG, "No se pudo abrir el enlace en " + packageName + ".");
            } catch (SecurityException e) {
                Log.w(TAG, "Android bloqueó la apertura de WhatsApp desde el servicio.", e);
                break;
            }
        }
        speak("No pude abrir WhatsApp. Comprueba que WhatsApp esté instalado y vuelve a intentarlo.");
    }

    private void clearPendingCallConfirmation() {
        awaitingCallConfirmation = false;
        pendingCallContactName = null;
        pendingCallPhone = null;
    }

    /** Devuelve el texto que sigue a la palabra llamar, eliminando conectores conocidos. */
    private String extractRequestedContactName(String recognizedText) {
        String normalized = normalizeWords(recognizedText);
        String[] words = normalized.split("\\s+");
        int callIndex = -1;
        for (int i = 0; i < words.length && i < 3; i++) {
            if (words[i].equals("llamar")) { callIndex = i; break; }
        }
        if (callIndex < 0) return "";
        ArrayList<String> nameWords = new ArrayList<>();
        for (int i = callIndex + 1; i < words.length; i++) {
            String word = words[i];
            if (word.isEmpty() || word.equals("a") || word.equals("por") || word.equals("favor") || word.equals("whatsapp")) continue;
            nameWords.add(word);
        }
        StringBuilder result = new StringBuilder();
        for (String word : nameWords) {
            if (result.length() > 0) result.append(' ');
            result.append(word);
        }
        return result.toString().trim();
    }

    /** Normaliza texto para comparar nombres sin tildes, signos ni diferencias de mayúsculas. */
    private String normalizeWords(String value) {
        if (value == null) return "";
        String normalized = Normalizer.normalize(value.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
            .replaceAll("\\p{M}+", "");
        return normalized.replaceAll("[^a-z0-9]+", " ").trim().replaceAll("\\s+", " ");
    }

    /** Acepta 0991234567, 991234567, 593991234567 y +593991234567. */
    private String normalizeEcuadorMobile(String rawPhone) {
        if (rawPhone == null) return null;
        String digits = rawPhone.replaceAll("\\D", "");
        if (digits.startsWith("593")) digits = digits.substring(3);
        if (digits.length() == 10 && digits.startsWith("09")) digits = digits.substring(1);
        if (digits.length() != 9 || !digits.startsWith("9")) return null;
        return "+593" + digits;
    }

    private ActionType classify(String text) {
        String normalized = Normalizer.normalize(text.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
            .replaceAll("\\p{M}+", "");
        String[] words = normalized.split("[^a-z0-9]+", -1);
        int callPosition = -1;
        int analysisPosition = -1;
        int messagePosition = -1;
        int wordCount = 0;
        for (String word : words) {
            if (word.isEmpty()) continue;
            wordCount++;
            if (wordCount > 3) break;
            if (callPosition < 0 && word.equals("llamar")) callPosition = wordCount;
            if (analysisPosition < 0 && word.equals("analisis")) analysisPosition = wordCount;
            if (messagePosition < 0 && word.equals("mensaje")) messagePosition = wordCount;
        }
        // CALL tiene prioridad si "llamar" aparece entre las tres primeras palabras.
        if (callPosition >= 0) return ActionType.CALL;
        // Si ambas palabras clave aparecen entre las tres primeras, se usa la que aparece antes.
        if (analysisPosition >= 0 && (messagePosition < 0 || analysisPosition < messagePosition)) return ActionType.ANALYSIS;
        if (messagePosition >= 0) return ActionType.MESSAGE;
        return ActionType.GENERAL;
    }

private String extractOutput(String response) throws JSONException {
    if (response == null || response.trim().isEmpty()) return "";

    Object parsed = new org.json.JSONTokener(response).nextValue();
    JSONObject object = null;

    if (parsed instanceof JSONArray) {
        JSONArray array = (JSONArray) parsed;
        if (array.length() > 0) {
            object = array.optJSONObject(0);
        }
    } else if (parsed instanceof JSONObject) {
        object = (JSONObject) parsed;
    }

    if (object == null) return "";

    // Acepta el formato actual del webhook y el formato anterior.
    String output = object.optString("texto", "").trim();

    if (output.isEmpty()) {
        output = object.optString("output", "").trim();
    }

    return output;
}

   
    private String readAll(InputStream input) throws Exception {
        if (input == null) return "";
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            return out.toString("UTF-8");
        }
    }

    private String errorMessage(int error) {
        switch (error) {
            case SpeechRecognizer.ERROR_AUDIO: return "Hubo un problema con el audio del micrófono.";
            case SpeechRecognizer.ERROR_CLIENT: return "Se interrumpió el reconocimiento de voz. Inténtalo otra vez.";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS: return "Falta el permiso del micrófono.";
            case SpeechRecognizer.ERROR_NETWORK:
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT: return "El servicio de reconocimiento necesita conexión a internet.";
            case SpeechRecognizer.ERROR_NO_MATCH:
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT: return "No detecté una frase clara. Inténtalo otra vez.";
            default: return "El reconocimiento de voz falló. Inténtalo otra vez.";
        }
    }

    private void speak(String text) {
        if (text == null || text.trim().isEmpty()) return;
        String message = text.trim();
        mainHandler.post(() -> {
            pendingSpeech = message;
            if (ttsReady && textToSpeech != null) speakPending();
        });
    }

    private void speakPending() {
        if (!ttsReady || textToSpeech == null || pendingSpeech == null) return;
        String text = pendingSpeech;
        pendingSpeech = null;
        textToSpeech.setLanguage(new Locale("es", "EC"));
        textToSpeech.setSpeechRate(1.0f);
        String utteranceId;
        if (awaitingCallConfirmation && text.toLowerCase(Locale.ROOT).contains("responde sí para confirmar")) {
            utteranceId = "leaden2-call-confirmation";
        } else if (pendingWhatsAppPhone != null
                && text.toLowerCase(Locale.ROOT).contains("whatsapp")
                && (text.toLowerCase(Locale.ROOT).contains("llamada")
                    || text.toLowerCase(Locale.ROOT).contains("abriré el chat")
                    || text.toLowerCase(Locale.ROOT).contains("intentare realizar")
                    || text.toLowerCase(Locale.ROOT).contains("intentare hacer")
                    || text.toLowerCase(Locale.ROOT).contains("iniciar automáticamente"))) {
            // Tras anunciar la acción confirmada, continuar con la apertura del chat.
            // El servicio de accesibilidad solo pulsará si verifica el contacto y el control.
            utteranceId = "leaden2-whatsapp-open";
        } else {
            utteranceId = "leaden2-response";
        }
        if (Build.VERSION.SDK_INT >= 21) textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId);
        else textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null);
    }

    @Override
    public void onInit(int status) {
        ttsReady = status == TextToSpeech.SUCCESS;
        if (ttsReady) {
            textToSpeech.setLanguage(new Locale("es", "EC"));
            textToSpeech.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String utteranceId) { }
                @Override public void onDone(String utteranceId) {
                    if ("leaden2-call-confirmation".equals(utteranceId)) {
                        mainHandler.post(() -> {
                            if (awaitingCallConfirmation) startListening();
                        });
                    } else if ("leaden2-whatsapp-open".equals(utteranceId)) {
                        mainHandler.post(() -> openPendingWhatsAppChat());
                    }
                }
                @Override public void onError(String utteranceId) {
                    if ("leaden2-call-confirmation".equals(utteranceId)) {
                        mainHandler.post(() -> {
                            if (awaitingCallConfirmation) {
                                clearPendingCallConfirmation();
                                speak("No pude reproducir la pregunta de confirmación. La operación ha sido cancelada.");
                            }
                        });
                    } else if ("leaden2-whatsapp-open".equals(utteranceId)) {
                        mainHandler.post(() -> {
                            pendingWhatsAppContactName = null;
                            pendingWhatsAppPhone = null;
                            speak("No pude reproducir la instrucción. No abrí WhatsApp ni inicié una llamada.");
                        });
                    }
                }
            });
            speakPending();
        } else Log.e(TAG, "No se pudo inicializar TextToSpeech.");
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Servicio de voz LEADEN2", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Mantiene preparado el asistente de voz para las gafas Bluetooth.");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    @Override
    public void onDestroy() {
        if (speechRecognizer != null) {
            try { speechRecognizer.cancel(); speechRecognizer.destroy(); } catch (Exception ignored) { }
            speechRecognizer = null;
        }
        if (textToSpeech != null) {
            textToSpeech.stop();
            textToSpeech.shutdown();
            textToSpeech = null;
        }
        networkExecutor.shutdownNow();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private enum ActionType { CALL, ANALYSIS, MESSAGE, GENERAL }
}
