package com.leaden2.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.graphics.Rect;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Automatización opcional y local de la interfaz de WhatsApp.
 * Solo actúa después de que LEADEN2 haya recibido un “sí” y armado el número validado.
 * Si no puede verificar el chat o identificar con claridad el control de llamada de voz,
 * no pulsa nada.
 */
public class WhatsAppCallAccessibilityService extends AccessibilityService {
    private static final String TAG = "LEADEN2WaAccess";
    private static final String PREFS = "leaden2_local_data";
    private static final long MAX_ARMED_MS = 45000L;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean checkQueued = false;

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                    | AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                    | AccessibilityEvent.TYPE_WINDOWS_CHANGED;
            info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
            info.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                    | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
            info.notificationTimeout = 250;
            setServiceInfo(info);
        }
        Log.i(TAG, "Servicio de accesibilidad habilitado por el usuario.");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null) return;
        String packageName = event.getPackageName().toString();
        if (!"com.whatsapp".equals(packageName) && !"com.whatsapp.w4b".equals(packageName)) return;
        scheduleInspection(500);
    }

    /**
     * Reintenta de forma limitada mientras la orden siga armada. WhatsApp puede tardar
     * en terminar de cargar el chat y el árbol de accesibilidad no siempre está listo
     * en el primer evento de ventana.
     */
    private void scheduleInspection(long delayMs) {
        if (checkQueued) return;
        checkQueued = true;
        handler.postDelayed(() -> {
            checkQueued = false;
            inspectWhatsAppWindow();
        }, delayMs);
    }

    private void inspectWhatsAppWindow() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (!prefs.getBoolean("wa_auto_call_armed", false)) return;

        long armedAt = prefs.getLong("wa_auto_call_armed_at", 0L);
        if (armedAt == 0L || System.currentTimeMillis() - armedAt > MAX_ARMED_MS) {
            disarm("Se agotó el tiempo de espera; no se inició la llamada.");
            return;
        }

        String expectedName = prefs.getString("wa_auto_call_name", "");
        String expectedPhone = digits(prefs.getString("wa_auto_call_phone", ""));
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (expectedPhone.isEmpty()) {
            Log.w(TAG, "La orden está armada pero no hay número esperado; no se pulsará nada.");
            disarm("No hay número validado para la llamada.");
            return;
        }
        if (root == null) {
            Log.d(TAG, "WhatsApp aún no ofrece una ventana accesible; se reintentará.");
            scheduleInspection(900);
            return;
        }

        if (!containsExpectedContact(root, expectedName, expectedPhone)) {
            Log.d(TAG, "El contacto visible aún no coincide con el contacto esperado; se reintentará sin pulsar.");
            scheduleInspection(900);
            return;
        }

        AccessibilityNodeInfo callNode = findVoiceCallControl(root);
        if (callNode == null) {
            Log.d(TAG, "El contacto coincide, pero aún no se identifica el control de llamada de voz; se reintentará sin pulsar.");
            scheduleInspection(900);
            return;
        }

        // Desarmar antes de pulsar para evitar dobles llamadas por eventos repetidos.
        prefs.edit().putBoolean("wa_auto_call_armed", false).apply();
        boolean clicked = clickSelfOrClickableParent(callNode);
        Log.i(TAG, clicked
                ? "Se pulsó el control identificado como llamada de voz de WhatsApp para el contacto validado."
                : "El control de llamada de voz fue identificado, pero Android no aceptó la acción.");
        if (!clicked) {
            // El estado se desarma antes del intento: no repetir un clic que podría generar una llamada duplicada.
            prefs.edit().putBoolean("wa_auto_call_armed", false).apply();
            Log.w(TAG, "No se pudo ejecutar ACTION_CLICK; la orden queda desarmada y no se reintentará para evitar una llamada duplicada.");
        }
    }

    private boolean containsExpectedContact(AccessibilityNodeInfo root, String expectedName, String expectedPhone) {
        String name = normalize(expectedName);
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        return containsContactInHeader(root, name, expectedPhone, screenHeight);
    }

    /**
     * WhatsApp no mantiene IDs de recursos estables entre versiones. En vez de exigir
     * que el ID contenga "title" (lo que puede impedir siempre la llamada), verifica
     * una coincidencia exacta del nombre o número en la franja superior de la pantalla.
     * Esto evita aceptar un mensaje del historial como prueba del contacto.
     */
    private boolean containsContactInHeader(AccessibilityNodeInfo node, String expectedName,
                                            String expectedPhone, int screenHeight) {
        if (node == null) return false;
        if (node.isVisibleToUser()) {
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            boolean inHeader = bounds.top >= 0 && bounds.top < (int) (screenHeight * 0.30f)
                    && bounds.bottom > bounds.top;
            if (inHeader && matchesContactText(node.getText(), node.getContentDescription(),
                    expectedName, expectedPhone)) {
                Log.i(TAG, "Contacto verificado mediante coincidencia exacta en la cabecera visible.");
                return true;
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            if (containsContactInHeader(node.getChild(i), expectedName, expectedPhone, screenHeight)) return true;
        }
        return false;
    }

    private boolean matchesContactText(CharSequence text, CharSequence desc, String expectedName, String expectedPhone) {
        return matchesOne(text, expectedName, expectedPhone) || matchesOne(desc, expectedName, expectedPhone);
    }

    private boolean matchesOne(CharSequence value, String expectedName, String expectedPhone) {
        if (value == null) return false;
        String normalized = normalize(value.toString());
        if (!expectedName.isEmpty() && normalized.equals(expectedName)) return true;
        String valueDigits = digits(value.toString());
        return !expectedPhone.isEmpty() && !valueDigits.isEmpty() && valueDigits.equals(expectedPhone);
    }

    private AccessibilityNodeInfo findVoiceCallControl(AccessibilityNodeInfo root) {
        if (root == null) return null;
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        return findVoiceCallControlRecursive(root, screenHeight);
    }

    private AccessibilityNodeInfo findVoiceCallControlRecursive(AccessibilityNodeInfo node, int screenHeight) {
        if (node == null) return null;
        String viewId = node.getViewIdResourceName();
        String text = node.getText() == null ? "" : normalize(node.getText().toString());
        String desc = node.getContentDescription() == null ? "" : normalize(node.getContentDescription().toString());
        String combined = ((viewId == null ? "" : viewId) + " " + text + " " + desc).toLowerCase(Locale.ROOT);
        boolean video = combined.contains("video") || combined.contains("videollamada")
                || combined.contains("video_call") || combined.contains("video call");
        boolean idLooksLikeCall = viewId != null
                && (viewId.toLowerCase(Locale.ROOT).contains("call")
                    || viewId.toLowerCase(Locale.ROOT).contains("voice")) && !video;
        boolean labelLooksLikeVoiceCall = !video && (
                desc.equals("llamada de voz") || desc.equals("llamar") || desc.equals("llamada")
                || desc.equals("llamada de voz por whatsapp") || desc.equals("voice call")
                || desc.equals("call") || desc.equals("make a call") || desc.equals("start voice call")
                || text.equals("llamada de voz") || text.equals("llamar") || text.equals("llamada")
                || text.equals("voice call") || text.equals("call"));
        if (node.isVisibleToUser() && node.isEnabled()) {
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            boolean inHeader = bounds.top >= 0 && bounds.top < (int) (screenHeight * 0.35f)
                    && bounds.bottom > bounds.top;
            if (inHeader && (idLooksLikeCall || labelLooksLikeVoiceCall)) {
                Log.i(TAG, "Control candidato de llamada localizado en la cabecera; se intentará ACTION_CLICK una sola vez.");
                return node;
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo found = findVoiceCallControlRecursive(node.getChild(i), screenHeight);
            if (found != null) return found;
        }
        return null;
    }

    private boolean clickSelfOrClickableParent(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = node;
        for (int depth = 0; current != null && depth < 5; depth++) {
            if (current.isVisibleToUser() && current.isEnabled() && current.isClickable()) {
                return current.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            }
            current = current.getParent();
        }
        return false;
    }

    private void disarm(String reason) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("wa_auto_call_armed", false).apply();
        Log.w(TAG, reason);
    }

    private String normalize(String input) {
        if (input == null) return "";
        String decomposed = Normalizer.normalize(input, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT);
        return decomposed.replaceAll("[^a-z0-9+ ]", " ").replaceAll("\\s+", " ").trim();
    }

    private String digits(String input) {
        return input == null ? "" : input.replaceAll("\\D", "");
    }

    @Override
    public void onInterrupt() {
        Log.w(TAG, "Servicio de accesibilidad interrumpido.");
    }
}
