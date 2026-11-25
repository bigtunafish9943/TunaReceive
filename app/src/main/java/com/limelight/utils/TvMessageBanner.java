package com.limelight.utils;

import android.app.Activity;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.view.View;
import android.widget.TextView;

import com.limelight.LimeLog;
import com.limelight.nvstream.http.NvHTTP;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.ResponseBody;

/**
 * Shows messages sent from TunaStream's home page over the game, one at a time, and plays the
 * sound-board sounds that come with them. Sounds are fetched from the PC when they arrive.
 */
public final class TvMessageBanner implements StreamReport.PcMessageListener {
    private static final long MIN_SHOW_MS = 6000;
    private static final long MAX_SHOW_MS = 15000;
    private static final long PER_CHAR_MS = 60;
    private static final long MAX_SOUND_BYTES = 4 * 1024 * 1024;

    private final Activity activity;
    private final TextView view;
    private final NvHTTP http;
    private final ArrayDeque<String> waiting = new ArrayDeque<>();
    private final ExecutorService soundLoader = Executors.newSingleThreadExecutor();
    private MediaPlayer player;
    private boolean showing;
    private boolean closed;

    public TvMessageBanner(Activity activity, TextView view, NvHTTP http) {
        this.activity = activity;
        this.view = view;
        this.http = http;
    }

    @Override
    public void onPcMessage(long id, String text, String sound) {
        LimeLog.info("Message from the PC (" + id + ")" + (sound.isEmpty() ? "" : " with sound " + sound));
        if (!sound.isEmpty()) {
            playSound(sound);
        }
        if (!text.isEmpty()) {
            activity.runOnUiThread(() -> {
                waiting.add(text);
                if (!showing) {
                    showNext();
                }
            });
        }
    }

    /** Shows a message from TunaReceive itself, such as a tip for this game. */
    public void showLocal(String text) {
        activity.runOnUiThread(() -> {
            waiting.add(text);
            if (!showing) {
                showNext();
            }
        });
    }

    private void showNext() {
        if (closed) {
            return;
        }
        String text = waiting.poll();
        if (text == null) {
            showing = false;
            view.animate().alpha(0f).setDuration(300).withEndAction(() -> {
                if (!showing) {
                    view.setVisibility(View.GONE);
                }
            });
            return;
        }
        showing = true;
        view.animate().cancel();
        view.setText(text);
        view.setContentDescription(text);
        if (view.getVisibility() != View.VISIBLE) {
            view.setAlpha(0f);
            view.setTranslationY(-24f);
            view.setVisibility(View.VISIBLE);
            view.animate().alpha(1f).translationY(0f).setDuration(250);
        }
        long showFor = Math.max(MIN_SHOW_MS, Math.min(MAX_SHOW_MS, text.length() * PER_CHAR_MS + 3000));
        view.postDelayed(this::showNext, showFor);
    }

    private void playSound(String name) {
        try {
            soundLoader.execute(() -> {
                File file = new File(activity.getCacheDir(), "tuna_sound");
                try (ResponseBody body = http.openTunaSound(name);
                     InputStream in = body.byteStream();
                     OutputStream out = new FileOutputStream(file)) {
                    byte[] buf = new byte[16384];
                    long total = 0;
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        total += n;
                        if (total > MAX_SOUND_BYTES) {
                            throw new java.io.IOException("sound too large");
                        }
                        out.write(buf, 0, n);
                    }
                } catch (Exception e) {
                    LimeLog.warning("Couldn't fetch sound " + name + ": " + e);
                    return;
                }
                activity.runOnUiThread(() -> start(file));
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // Closing
        }
    }

    private void start(File file) {
        if (closed) {
            return;
        }
        stopSound();
        try {
            MediaPlayer mp = new MediaPlayer();
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
            mp.setDataSource(file.getAbsolutePath());
            mp.setOnCompletionListener(MediaPlayer::release);
            mp.setOnErrorListener((m, what, extra) -> {
                m.release();
                return true;
            });
            mp.prepare();
            mp.start();
            player = mp;
        } catch (Exception e) {
            LimeLog.warning("Couldn't play sound: " + e);
        }
    }

    private void stopSound() {
        if (player != null) {
            try {
                player.release();
            } catch (Exception ignored) {
            }
            player = null;
        }
    }

    /** When the stream ends. */
    public void close() {
        closed = true;
        soundLoader.shutdownNow();
        activity.runOnUiThread(() -> {
            view.setVisibility(View.GONE);
            stopSound();
        });
    }
}
