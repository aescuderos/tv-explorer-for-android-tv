package com.tvexplorer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.interfaces.IVLCVout;

import java.util.ArrayList;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

public class PlayerActivity extends Activity {
    static final String EXTRA_NAME = "name", EXTRA_URLS = "urls", EXTRA_INDEX = "index";
    private static final long CONNECT_TIMEOUT_MS = 25000;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private ArrayList<String> urls;
    private String name;
    private int index, attempts;
    private boolean started, paused, focused, error, fullscreen;
    private int originalOrientation;
    private LibVLC vlc;
    private MediaPlayer player;
    private AudioManager audio;
    private FrameLayout stage;
    private SurfaceView surface;
    private LinearLayout overlay;
    private TextView message;
    private ImageButton toggle, fullscreenButton;
    private int visibleWidth, visibleHeight, sarNum = 1, sarDen = 1;
    private Runnable timeout;
    private final Runnable hideControls = () -> overlay.setVisibility(View.GONE);
    private final AudioManager.OnAudioFocusChangeListener focusListener = change -> {
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
            if (change == AudioManager.AUDIOFOCUS_LOSS) focused = false;
            pausePlayback();
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        name = getIntent().getStringExtra(EXTRA_NAME);
        urls = getIntent().getStringArrayListExtra(EXTRA_URLS);
        if (name == null || urls == null || urls.isEmpty()) { finish(); return; }
        index = state == null ? getIntent().getIntExtra(EXTRA_INDEX, 0) : state.getInt("index");
        index = Math.max(0, Math.min(index, urls.size() - 1));
        paused = state != null && state.getBoolean("paused");
        fullscreen = state != null && state.getBoolean("fullscreen");
        originalOrientation = state == null ? getRequestedOrientation() : state.getInt("originalOrientation");
        audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        buildUi();
        if (fullscreen) applyFullscreen();
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private void buildUi() {
        stage = new FrameLayout(this);
        stage.setBackgroundColor(Color.BLACK);
        surface = new SurfaceView(this);
        stage.addView(surface, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        stage.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, oright, ob) -> resizeVideo());
        overlay = new LinearLayout(this);
        overlay.setOrientation(LinearLayout.VERTICAL);
        overlay.setPadding(dp(20), dp(14), dp(20), dp(14));
        overlay.setBackgroundColor(0xCC181314);
        TextView title = new TextView(this);
        title.setText(name);
        title.setTextColor(Color.WHITE);
        title.setTextSize(20);
        overlay.addView(title);
        message = new TextView(this);
        message.setTextColor(Color.LTGRAY);
        message.setTextSize(15);
        message.setPadding(0, dp(4), 0, dp(8));
        overlay.addView(message);
        LinearLayout controls = new LinearLayout(this);
        controls.addView(iconButton(R.drawable.player_back, "Volver", v -> finish()),
                new LinearLayout.LayoutParams(dp(56), dp(56)));
        controls.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        toggle = iconButton(R.drawable.player_pause, "Pausar", v -> {
            if (paused || error) resumePlayback(); else pausePlayback();
        });
        controls.addView(toggle, new LinearLayout.LayoutParams(dp(56), dp(56)));
        controls.addView(iconButton(R.drawable.player_next, "Emisiones alternativas", v -> chooseStream()),
                new LinearLayout.LayoutParams(dp(56), dp(56)));
        fullscreenButton = iconButton(R.drawable.player_fullscreen, "Pantalla completa", v -> {
            fullscreen = !fullscreen;
            applyFullscreen();
            showControls();
        });
        controls.addView(fullscreenButton, new LinearLayout.LayoutParams(dp(56), dp(56)));
        controls.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        controls.addView(iconButton(R.drawable.player_info, "Información sobre LibVLC", v -> showLicense()),
                new LinearLayout.LayoutParams(dp(56), dp(56)));
        overlay.addView(controls);
        stage.addView(overlay, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        stage.setOnClickListener(v -> showControls());
        setContentView(stage);
        toggle.requestFocus();
    }

    private void chooseStream() {
        ui.removeCallbacks(hideControls);
        String[] labels = new String[urls.size()];
        for (int i = 0; i < urls.size(); i++)
            labels[i] = "Emisión " + (i + 1) + (i == index ? " (actual)" : "");
        new AlertDialog.Builder(this).setTitle("Emisiones disponibles")
                .setItems(labels, (dialog, selected) -> {
                    if (selected == index && !error) { showControls(); return; }
                    index = selected;
                    paused = false;
                    attempts = 0;
                    attempt();
                }).setNegativeButton("Cancelar", (dialog, which) -> showControls())
                .setOnCancelListener(dialog -> showControls()).show();
    }

    @SuppressWarnings("deprecation")
    private void applyFullscreen() {
        int flags = fullscreen ? View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION : 0;
        getWindow().getDecorView().setSystemUiVisibility(flags);
        fullscreenButton.setImageResource(fullscreen ? R.drawable.player_fullscreen_exit : R.drawable.player_fullscreen);
        fullscreenButton.setContentDescription(fullscreen ? "Salir de pantalla completa" : "Pantalla completa");
        setRequestedOrientation(fullscreen ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE : originalOrientation);
    }

    private void showLicense() {
        try (InputStream input = getAssets().open("licenses/LGPL-2.1.txt");
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            new AlertDialog.Builder(this).setTitle("LibVLC — LGPL 2.1")
                    .setMessage("LibVLC © VideoLAN y colaboradores. Código: https://code.videolan.org/videolan/vlc-android\n\n"
                            + output.toString("UTF-8"))
                    .setPositiveButton("Cerrar", null).show();
        } catch (IOException e) {
            Log.e("TVExplorer", "Cannot read LibVLC license", e);
            new AlertDialog.Builder(this).setMessage("No se pudo abrir la licencia de LibVLC.")
                    .setPositiveButton("Cerrar", null).show();
        }
    }

    private ImageButton iconButton(int icon, String label, View.OnClickListener listener) {
        ImageButton b = new ImageButton(this);
        TypedValue background = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, background, true);
        b.setBackgroundResource(background.resourceId);
        b.setImageResource(icon);
        b.setContentDescription(label);
        b.setPadding(dp(16), dp(16), dp(16), dp(16));
        b.setOnClickListener(listener);
        return b;
    }

    private void setToggle(boolean play, String label) {
        toggle.setImageResource(play ? R.drawable.player_play : R.drawable.player_pause);
        toggle.setContentDescription(label);
    }

    private void showControls() {
        overlay.setVisibility(View.VISIBLE);
        ui.removeCallbacks(hideControls);
        if (!paused && !error) ui.postDelayed(hideControls, 5000);
    }

    private void resizeVideo() {
        int w = stage.getWidth(), h = stage.getHeight();
        if (w <= 0 || h <= 0 || player == null) return;
        int targetW = w, targetH = h;
        if (visibleWidth > 0 && visibleHeight > 0 && sarNum > 0 && sarDen > 0) {
            double ratio = (double) visibleWidth * sarNum / (visibleHeight * (double) sarDen);
            if ((double) w / h > ratio) targetW = (int) Math.round(h * ratio);
            else targetH = (int) Math.round(w / ratio);
        }
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) surface.getLayoutParams();
        if (params.width != targetW || params.height != targetH) {
            params.width = targetW;
            params.height = targetH;
            surface.setLayoutParams(params);
        }
        player.getVLCVout().setWindowSize(targetW, targetH);
    }

    @Override protected void onStart() {
        super.onStart();
        if (urls == null || urls.isEmpty()) return;
        started = true;
        try {
            vlc = new LibVLC(this);
            if (paused) {
                error = false;
                setToggle(true, "Reproducir");
                message.setText("En pausa");
            } else {
                attempts = 0;
                attempt();
            }
        } catch (RuntimeException | LinkageError e) {
            Log.e("TVExplorer", "Cannot initialize LibVLC", e);
            showError("No se pudo iniciar el reproductor.");
        }
    }

    private boolean requestFocus() {
        if (focused) return true;
        focused = audio.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        if (!focused) showError("No se pudo obtener el control del audio.");
        return focused;
    }

    private void attempt() {
        if (!started || vlc == null || !requestFocus()) return;
        cancelTimeout();
        releasePlayer();
        error = false;
        paused = false;
        attempts++;
        visibleWidth = visibleHeight = 0;
        setToggle(false, "Pausar");
        message.setText("Conectando · emisión " + (index + 1) + "/" + urls.size());
        showControls();
        String url = urls.get(index);
        Uri uri = Uri.parse(url);
        if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
            failed("Dirección de emisión no compatible.");
            return;
        }
        try {
            final MediaPlayer current = new MediaPlayer(vlc);
            player = current;
            IVLCVout output = current.getVLCVout();
            output.setVideoView(surface);
            output.attachViews((vout, width, height, visibleW, visibleH, num, den) -> {
                if (current != player || !started) return;
                visibleWidth = visibleW > 0 ? visibleW : width;
                visibleHeight = visibleH > 0 ? visibleH : height;
                sarNum = num > 0 ? num : 1;
                sarDen = den > 0 ? den : 1;
                resizeVideo();
            });
            resizeVideo();
            current.setEventListener(event -> {
                if (current != player || !started || paused) return;
                if (event.type == MediaPlayer.Event.Playing) {
                    cancelTimeout();
                    message.setText("En directo · emisión " + (index + 1) + "/" + urls.size());
                    showControls();
                } else if (event.type == MediaPlayer.Event.EncounteredError
                        || event.type == MediaPlayer.Event.EndReached) {
                    ui.post(() -> { if (current == player && started && !paused) failed("La emisión no responde."); });
                }
            });
            Media media = new Media(vlc, uri);
            try {
                media.setHWDecoderEnabled(true, false);
                current.setMedia(media);
            } finally { media.release(); }
            timeout = () -> { if (current == player && started && !paused) failed("La emisión tarda demasiado."); };
            ui.postDelayed(timeout, CONNECT_TIMEOUT_MS);
            current.play();
        } catch (RuntimeException | LinkageError e) {
            Log.e("TVExplorer", "Cannot play stream", e);
            failed("No se pudo iniciar la emisión.");
        }
    }

    private void failed(String reason) {
        cancelTimeout();
        releasePlayer();
        if (attempts < urls.size()) {
            index = (index + 1) % urls.size();
            message.setText(reason + " Probando otra…");
            ui.post(this::attempt);
        } else {
            showError(reason + " No hay más emisiones disponibles.");
        }
    }

    private void showError(String text) {
        error = true;
        paused = true;
        cancelTimeout();
        setToggle(true, "Reintentar");
        message.setText(text);
        showControls();
        toggle.requestFocus();
        abandonFocus();
    }

    private void pausePlayback() {
        if (paused || !started) return;
        paused = true;
        cancelTimeout();
        if (player != null) player.pause();
        setToggle(true, "Reproducir");
        message.setText("En pausa");
        showControls();
        abandonFocus();
    }

    private void resumePlayback() {
        if (!started || vlc == null) {
            showError("No se pudo iniciar el reproductor.");
            return;
        }
        attempts = 0;
        paused = false;
        attempt();
    }

    private void cancelTimeout() {
        if (timeout != null) ui.removeCallbacks(timeout);
        timeout = null;
    }

    private void abandonFocus() {
        if (focused) audio.abandonAudioFocus(focusListener);
        focused = false;
    }

    private void releasePlayer() {
        if (player == null) return;
        MediaPlayer old = player;
        player = null;
        old.setEventListener(null);
        old.stop();
        if (old.getVLCVout().areViewsAttached()) old.getVLCVout().detachViews();
        old.release();
    }

    @Override public boolean onKeyDown(int key, KeyEvent event) {
        if (key == KeyEvent.KEYCODE_BUTTON_B) { finish(); return true; }
        if (overlay.getVisibility() != View.VISIBLE && (key == KeyEvent.KEYCODE_DPAD_CENTER
                || key == KeyEvent.KEYCODE_ENTER || key == KeyEvent.KEYCODE_BUTTON_A
                || key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN
                || key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            showControls();
            toggle.requestFocus();
            return true;
        }
        if (key == KeyEvent.KEYCODE_BUTTON_A) {
            View focus = getCurrentFocus();
            if (focus != null) focus.performClick();
            return true;
        }
        if (key == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
            if (paused || error) resumePlayback(); else pausePlayback();
            return true;
        }
        return super.onKeyDown(key, event);
    }

    @Override protected void onStop() {
        started = false;
        cancelTimeout();
        ui.removeCallbacksAndMessages(null);
        releasePlayer();
        abandonFocus();
        if (vlc != null) { vlc.release(); vlc = null; }
        super.onStop();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putInt("index", index);
        state.putBoolean("paused", paused);
        state.putBoolean("fullscreen", fullscreen);
        state.putInt("originalOrientation", originalOrientation);
        super.onSaveInstanceState(state);
    }
}
