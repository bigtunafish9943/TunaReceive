package com.limelight.utils;

import android.content.Context;
import android.graphics.Typeface;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.view.View;
import android.widget.TextView;

import com.limelight.R;
import com.limelight.binding.video.MediaCodecDecoderRenderer;
import com.limelight.nvstream.jni.MoonBridge;

import org.json.JSONObject;

import java.util.Locale;

/**
 * The stream details overlay: what the stream is doing right now, in plain words, updated every
 * second. Picture size and format, frame rate (target, now, average, and what the game itself
 * draws), delay, how steady the connection is, the TV's Wi-Fi, and the PC's side.
 */
public final class StreamDetailsOverlay {
    private static final int LABEL_COLOR = 0xFF7FD8F7;
    private static final int GOOD = 0xFF7BE0A1;
    private static final int FAIR = 0xFFFFD166;
    private static final int POOR = 0xFFFF7A7A;

    private final Context context;
    private final TextView view;
    private final StreamReport report;
    private final MediaCodecDecoderRenderer decoder;
    private boolean showing;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!showing) {
                return;
            }
            update();
            view.postDelayed(this, 1000);
        }
    };

    public StreamDetailsOverlay(Context context, TextView view, StreamReport report, MediaCodecDecoderRenderer decoder) {
        this.context = context;
        this.view = view;
        this.report = report;
        this.decoder = decoder;
    }

    public void toggle() {
        showing = !showing;
        view.removeCallbacks(tick);
        if (showing) {
            update();
            view.setVisibility(View.VISIBLE);
            view.postDelayed(tick, 1000);
        } else {
            view.setVisibility(View.GONE);
        }
    }

    public void close() {
        showing = false;
        view.removeCallbacks(tick);
        view.setVisibility(View.GONE);
    }

    private void line(SpannableStringBuilder sb, int label, CharSequence value) {
        if (sb.length() > 0) {
            sb.append('\n');
        }
        int start = sb.length();
        sb.append(context.getString(label));
        sb.setSpan(new ForegroundColorSpan(LABEL_COLOR), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new StyleSpan(Typeface.BOLD), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.append("   ").append(value);
    }

    private static CharSequence colored(String text, int color) {
        SpannableStringBuilder sb = new SpannableStringBuilder(text);
        sb.setSpan(new ForegroundColorSpan(color), 0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sb;
    }

    private void update() {
        StreamReport.Live l = report.live();
        SpannableStringBuilder sb = new SpannableStringBuilder();

        int title = sb.length();
        sb.append(context.getString(R.string.details_title));
        sb.setSpan(new StyleSpan(Typeface.BOLD), title, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new RelativeSizeSpan(1.15f), title, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        // Picture
        String format = "";
        if (decoder != null) {
            int f = decoder.getActiveVideoFormat();
            String codec = (f & MoonBridge.VIDEO_FORMAT_MASK_AV1) != 0 ? "AV1" : (f & MoonBridge.VIDEO_FORMAT_MASK_H265) != 0 ? "HEVC" : "H.264";
            format = codec + ((f & MoonBridge.VIDEO_FORMAT_MASK_10BIT) != 0 ? ", 10-bit" : ", 8-bit");
        }
        String picture = context.getString(R.string.details_picture, l.width, l.height, format);
        if (l.scalePercent > 0 && l.scalePercent < 100) {
            picture += context.getString(R.string.details_picture_scaled, l.width * l.scalePercent / 100, l.height * l.scalePercent / 100);
        }
        line(sb, R.string.details_picture_label, picture);

        // Frame rate
        SpannableStringBuilder fps = new SpannableStringBuilder(context.getString(R.string.details_fps, l.targetFps));
        fps.append(colored(String.format(Locale.getDefault(), "%.1f", l.fps), l.fps >= l.targetFps * 0.95f ? GOOD : l.fps >= l.targetFps * 0.8f ? FAIR : POOR));
        fps.append(context.getString(R.string.details_fps_average, l.averageFps));
        if (l.pc != null && l.pc.optDouble("fps", 0) > 0) {
            fps.append(context.getString(R.string.details_fps_pc, l.pc.optDouble("fps")));
        }
        line(sb, R.string.details_fps_label, fps);

        // Delay
        long rttInfo = MoonBridge.getEstimatedRttInfo();
        int rtt = (int) (rttInfo >> 32);
        int variance = (int) (rttInfo & 0xFFFFFFFFL);
        String delay = context.getString(R.string.details_delay, l.decodeMs,
                decoder != null ? decoder.getAverageEndToEndLatency() : 0);
        if (rttInfo != -1 && rtt > 0) {
            delay += context.getString(R.string.details_delay_network, rtt, variance);
        }
        line(sb, R.string.details_delay_label, delay);

        // How steady
        int steadyColor = l.latePct30 < 1 && l.lossSeconds == 0 ? GOOD : l.latePct30 < 5 ? FAIR : POOR;
        String steadyWord = context.getString(steadyColor == GOOD ? R.string.details_steady_good
                : steadyColor == FAIR ? R.string.details_steady_fair : R.string.details_steady_poor);
        SpannableStringBuilder steady = new SpannableStringBuilder(colored(steadyWord, steadyColor));
        steady.append(context.getString(R.string.details_steady, l.latePct30, l.lossSeconds));
        line(sb, R.string.details_steady_label, steady);

        // Bitrate
        line(sb, R.string.details_bitrate_label, context.getString(R.string.details_bitrate,
                Math.round(l.kbps / 1000f), Math.round(l.settingKbps / 1000f)));

        // The TV's connection
        line(sb, R.string.details_tv_network_label, tvNetwork());

        // The PC's side
        if (l.pc != null) {
            String pc = context.getString(R.string.details_pc, l.pc.optDouble("pcMsAvg", 0), l.pc.optInt("fecPercent", 0));
            if (l.pc.optBoolean("splitFrames", false)) {
                pc += context.getString(R.string.details_pc_split);
            }
            line(sb, R.string.details_pc_label, pc);
        }

        sb.append('\n');
        int hint = sb.length();
        sb.append(context.getString(R.string.details_hint, Shortcuts.label(context, Shortcuts.STATS)));
        sb.setSpan(new RelativeSizeSpan(0.85f), hint, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new ForegroundColorSpan(0xFF9AB0C6), hint, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        view.setText(sb);
    }

    private String tvNetwork() {
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && cm != null) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(cm.getActiveNetwork());
                if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                    return context.getString(R.string.details_wired);
                }
            }
            WifiManager wifi = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            WifiInfo info = wifi != null ? wifi.getConnectionInfo() : null;
            if (info == null || info.getNetworkId() == -1) {
                return context.getString(R.string.details_unknown_network);
            }
            int signal = WifiManager.calculateSignalLevel(info.getRssi(), 101);
            String band = "";
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                int mhz = info.getFrequency();
                band = mhz >= 5925 ? "6 GHz" : mhz >= 4900 ? "5 GHz" : "2.4 GHz";
            }
            return context.getString(R.string.details_wifi, band, signal, info.getRssi(), info.getLinkSpeed());
        } catch (Exception e) {
            return context.getString(R.string.details_unknown_network);
        }
    }
}
