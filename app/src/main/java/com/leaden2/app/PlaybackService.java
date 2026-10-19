package com.leaden2.app;

import android.content.Intent;
import android.net.Uri;
import android.util.Log;
import android.speech.SpeechRecognizer;

import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.ForwardingPlayer;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSession.ControllerInfo;
import androidx.media3.session.MediaSessionService;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

public class PlaybackService extends MediaSessionService {
    public static final String ACTION_ACTIVATE = "com.leaden2.app.ACTION_ACTIVATE";
    public static final String ACTION_ASSISTANT = "com.leaden2.app.ACTION_ASSISTANT";
    public static final String ACTION_ENABLE = "com.leaden2.app.ACTION_ENABLE";
    public static final String ACTION_DEACTIVATE = "com.leaden2.app.ACTION_DEACTIVATE";
    private static final String TAG = "LEADEN2Playback";

    private ExoPlayer exoPlayer;
    private MediaSession mediaSession;
    private boolean activated = false;
    private boolean handlingAssistantAction = false;
    private boolean listenAfterCue = false;

    @Override
    public void onCreate() {
        super.onCreate();

        AudioAttributes audioAttributes = new AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
            .build();

        exoPlayer = new ExoPlayer.Builder(this).build();
        exoPlayer.setAudioAttributes(audioAttributes, true);
        exoPlayer.setHandleAudioBecomingNoisy(true);
        exoPlayer.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int playbackState) {
                if (playbackState == Player.STATE_ENDED && listenAfterCue) {
                    listenAfterCue = false;
                    requestVoiceListening();
                }
            }
        });

        Player sessionPlayer = new ForwardingPlayer(exoPlayer) {
            @Override
            public void play() {
                if (activated && !handlingAssistantAction) {
                    runAssistantAction("PLAY");
                } else {
                    super.play();
                }
            }

            @Override
            public void pause() {
                if (activated && !handlingAssistantAction) {
                    runAssistantAction("PAUSE");
                } else {
                    super.pause();
                }
            }

            @Override
            public void setPlayWhenReady(boolean playWhenReady) {
                if (activated && !handlingAssistantAction) {
                    runAssistantAction(playWhenReady ? "PLAY_WHEN_READY" : "PAUSE_WHEN_READY");
                } else {
                    super.setPlayWhenReady(playWhenReady);
                }
            }
        };

        mediaSession = new MediaSession.Builder(this, sessionPlayer).build();
        Log.i(TAG, "MediaSession nativa creada.");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        int result = super.onStartCommand(intent, flags, startId);
        if (intent != null) {
            String action = intent.getAction();
            if (ACTION_ACTIVATE.equals(action)) {
                activateAssistant();
            } else if (ACTION_ASSISTANT.equals(action)) {
                runAssistantAction("LOCAL");
            } else if (ACTION_ENABLE.equals(action)) {
                activated = true;
                Log.i(TAG, "LEADEN2 habilitado sin reproducir el audio inicial.");
            } else if (ACTION_DEACTIVATE.equals(action)) {
                deactivateAssistant();
                stopSelf();
                return START_NOT_STICKY;
            }
        }
        return result;
    }

    private void activateAssistant() {
        try {
            activated = true;
            File initialAudio = copyAssetIfNeeded("comienza_el_ensamblaje.mp3");
            exoPlayer.setMediaItem(MediaItem.fromUri(Uri.fromFile(initialAudio)));
            exoPlayer.prepare();
            exoPlayer.play();
            Log.i(TAG, "LEADEN2 activado: reproduciendo audio inicial.");
        } catch (IOException e) {
            Log.e(TAG, "No se pudo preparar el audio inicial.", e);
        }
    }

    private void deactivateAssistant() {
        activated = false;
        listenAfterCue = false;
        handlingAssistantAction = false;
        if (exoPlayer != null) {
            exoPlayer.stop();
            exoPlayer.clearMediaItems();
        }
        stopService(new Intent(this, VoiceAssistantService.class));
        Log.i(TAG, "LEADEN2 desactivado por el usuario.");
    }

    private void runAssistantAction(String source) {
        if (handlingAssistantAction) return;
        handlingAssistantAction = true;
        try {
            File actionAudio = copyAssetIfNeeded("orden.mp3");
            listenAfterCue = true;
            exoPlayer.stop();
            exoPlayer.clearMediaItems();
            exoPlayer.setMediaItem(MediaItem.fromUri(Uri.fromFile(actionAudio)));
            exoPlayer.prepare();
            exoPlayer.play();
            Log.i(TAG, "Acción del asistente iniciada. Origen: " + source);
        } catch (IOException e) {
            listenAfterCue = false;
            Log.e(TAG, "No se pudo reproducir orden.mp3.", e);
            requestVoiceListening();
        } finally {
            handlingAssistantAction = false;
        }
    }

    private void requestVoiceListening() {
        try {
            Intent voiceIntent = new Intent(this, VoiceAssistantService.class);
            voiceIntent.setAction(VoiceAssistantService.ACTION_LISTEN);
            startService(voiceIntent);
            Log.i(TAG, "Solicitud de reconocimiento de voz enviada.");
        } catch (Exception e) {
            Log.e(TAG, "No se pudo activar el reconocimiento de voz. Abre LEADEN2 y pulsa ACTIVAR LEADEN2.", e);
        }
    }

    private File copyAssetIfNeeded(String assetName) throws IOException {
        File target = new File(getFilesDir(), assetName);
        if (target.exists() && target.length() > 0) return target;

        try (InputStream input = getAssets().open(assetName);
             FileOutputStream output = new FileOutputStream(target)) {
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            output.flush();
        }
        if (!target.exists() || target.length() == 0) {
            throw new IOException("El asset quedó vacío: " + assetName);
        }
        return target;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // La sesión de reproducción puede seguir activa; no se detiene automáticamente.
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        if (mediaSession != null) {
            mediaSession.release();
            mediaSession = null;
        }
        if (exoPlayer != null) {
            exoPlayer.release();
            exoPlayer = null;
        }
        super.onDestroy();
    }

    @Override
    public MediaSession onGetSession(ControllerInfo controllerInfo) {
        return mediaSession;
    }
}
