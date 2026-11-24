package com.limelight.preferences;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import com.limelight.LimeLog;
import com.limelight.R;

import java.util.ArrayList;
import java.util.List;

/**
 * First-run setup, and "Run setup again" in Settings. A few questions answered with the
 * controller (or by touch) choose the picture size, frame rate, picture quality, controller
 * and sound settings, so most people never need the full settings page.
 */
public class SetupActivity extends AppCompatActivity {
    public static final String SETUP_COMPLETE_PREF = "tuna_setup_complete";
    public static final String CONTROLLER_PREF = "tuna_controller";
    public static final String PICTURE_PREF = "tuna_picture";
    public static final String OPEN_ONLY_PC_PREF = "tuna_open_only_pc";

    private enum Step { SCREEN, FRAME_RATE, PICTURE, CONTROLLER, SOUND, DONE }

    private static final class Option {
        final String value;
        final String title;
        final String detail;

        Option(String value, String title, String detail) {
            this.value = value;
            this.title = title;
            this.detail = detail;
        }
    }

    // What this device can do, found once when setup opens
    private int maxWidth;
    private int maxHeight;
    private boolean supports120;
    private String connection;          // ethernet, wifi6, wifi5, wifi24, wifi or unknown
    private String detectedController;  // a value from the controller step, or null
    private String detectedControllerName;

    // The answers
    private String resolution;
    private String fps;
    private String picture;
    private String controller;
    private String audio;

    private final List<Step> steps = new ArrayList<>();
    private int stepIndex;

    private TextView progressView;
    private TextView titleView;
    private TextView bodyView;
    private LinearLayout optionsView;

    public static boolean isSetupComplete(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context).getBoolean(SETUP_COMPLETE_PREF, false);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        detectDisplay();
        detectConnection();
        detectController();

        steps.add(Step.SCREEN);
        if (supports120) {
            steps.add(Step.FRAME_RATE);
        }
        steps.add(Step.PICTURE);
        steps.add(Step.CONTROLLER);
        steps.add(Step.SOUND);
        steps.add(Step.DONE);

        buildLayout();

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (stepIndex > 0) {
                    stepIndex--;
                    showStep();
                } else {
                    // Leaving on the first question: don't ask again on every start
                    PreferenceManager.getDefaultSharedPreferences(SetupActivity.this).edit()
                            .putBoolean(SETUP_COMPLETE_PREF, true).apply();
                    Toast.makeText(SetupActivity.this, R.string.setup_skipped, Toast.LENGTH_LONG).show();
                    finish();
                }
            }
        });

        showStep();
    }

    // ------------------------------------------------------------------ detection

    private void detectDisplay() {
        Display display = getWindowManager().getDefaultDisplay();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            for (Display.Mode mode : display.getSupportedModes()) {
                int w = Math.max(mode.getPhysicalWidth(), mode.getPhysicalHeight());
                int h = Math.min(mode.getPhysicalWidth(), mode.getPhysicalHeight());
                if ((long) w * h > (long) maxWidth * maxHeight) {
                    maxWidth = w;
                    maxHeight = h;
                }
                if (mode.getRefreshRate() >= 119f) {
                    supports120 = true;
                }
            }
        } else {
            DisplayMetrics metrics = new DisplayMetrics();
            display.getRealMetrics(metrics);
            maxWidth = Math.max(metrics.widthPixels, metrics.heightPixels);
            maxHeight = Math.min(metrics.widthPixels, metrics.heightPixels);
            supports120 = display.getRefreshRate() >= 119f;
        }
        if (maxWidth == 0 || maxHeight == 0) {
            maxWidth = 1920;
            maxHeight = 1080;
        }
    }

    private void detectConnection() {
        connection = "unknown";
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                return;
            }
            Network network = cm.getActiveNetwork();
            NetworkCapabilities caps = network != null ? cm.getNetworkCapabilities(network) : null;
            if (caps == null) {
                return;
            }
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                connection = "ethernet";
            } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                connection = "wifi";
                WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                @SuppressWarnings("deprecation")
                WifiInfo info = wifi != null ? wifi.getConnectionInfo() : null;
                int mhz = info != null ? info.getFrequency() : 0;
                if (mhz >= 5925) {
                    connection = "wifi6";
                } else if (mhz >= 4900) {
                    connection = "wifi5";
                } else if (mhz > 0) {
                    connection = "wifi24";
                }
            }
        } catch (RuntimeException e) {
            LimeLog.warning("Setup: couldn't read the connection type: " + e);
        }
    }

    private void detectController() {
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice device = InputDevice.getDevice(id);
            if (device == null || device.isVirtual()) {
                continue;
            }
            int sources = device.getSources();
            boolean gamepad = (sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                    || (sources & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
            if (!gamepad) {
                continue;
            }
            String name = device.getName() != null ? device.getName() : "";
            int vendor = device.getVendorId();
            String kind = null;
            if (vendor == 0x2dc8 || name.toLowerCase().contains("8bitdo")) {
                kind = "8bitdo";
            } else if (vendor == 0x045e || name.toLowerCase().contains("xbox")) {
                kind = "xbox";
            } else if (vendor == 0x054c || name.toLowerCase().contains("dualsense")
                    || name.toLowerCase().contains("wireless controller")) {
                kind = "playstation";
            } else if (vendor == 0x057e) {
                kind = "switch";
            }
            if (kind != null) {
                detectedController = kind;
                detectedControllerName = name;
                return;
            }
        }
    }

    private boolean wired() {
        return "ethernet".equals(connection);
    }

    // ------------------------------------------------------------------ layout

    private int dp(float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics()));
    }

    private void buildLayout() {
        int navy = ContextCompat.getColor(this, R.color.ts_navy);
        int text = ContextCompat.getColor(this, R.color.ts_text);
        int secondary = ContextCompat.getColor(this, R.color.ts_text_secondary);
        int accentSoft = ContextCompat.getColor(this, R.color.ts_accent_soft);

        boolean wide = getResources().getConfiguration().screenWidthDp >= 720;

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(navy);
        // TVs crop the edges of the picture, so keep everything well inside
        int edge = dp(wide ? 48 : 20);
        root.setPadding(edge, dp(wide ? 36 : 24), edge, dp(wide ? 36 : 24));

        LinearLayout columns = new LinearLayout(this);
        columns.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        root.addView(columns, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout question = new LinearLayout(this);
        question.setOrientation(LinearLayout.VERTICAL);
        question.setGravity(wide ? Gravity.CENTER_VERTICAL : Gravity.TOP);
        question.setPadding(0, 0, wide ? dp(40) : 0, wide ? 0 : dp(16));

        progressView = new TextView(this);
        progressView.setTextColor(accentSoft);
        progressView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        question.addView(progressView);

        titleView = new TextView(this);
        titleView.setTextColor(text);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, wide ? 34 : 28);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setPadding(0, dp(8), 0, dp(14));
        question.addView(titleView);

        bodyView = new TextView(this);
        bodyView.setTextColor(secondary);
        bodyView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        bodyView.setLineSpacing(0, 1.25f);
        question.addView(bodyView);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        optionsView = new LinearLayout(this);
        optionsView.setOrientation(LinearLayout.VERTICAL);
        optionsView.setGravity(wide ? Gravity.CENTER_VERTICAL : Gravity.TOP);
        // Room for the focus outline, which is drawn at the edge of each answer
        optionsView.setPadding(dp(4), dp(4), dp(4), dp(4));
        scroll.addView(optionsView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        if (wide) {
            columns.addView(question, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
            columns.addView(scroll, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        } else {
            columns.addView(question, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            columns.addView(scroll, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        }

        setContentView(root);
    }

    private View makeOption(Option option, boolean recommended) {
        int text = ContextCompat.getColor(this, R.color.ts_text);
        int secondary = ContextCompat.getColor(this, R.color.ts_text_secondary);
        int accent = ContextCompat.getColor(this, R.color.ts_accent);

        SpannableStringBuilder label = new SpannableStringBuilder();
        if (recommended) {
            int start = label.length();
            label.append(getString(R.string.setup_recommended)).append('\n');
            label.setSpan(new ForegroundColorSpan(accent), start, label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            label.setSpan(new AbsoluteSizeSpan(14, true), start, label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        int start = label.length();
        label.append(option.title);
        label.setSpan(new StyleSpan(Typeface.BOLD), start, label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        label.setSpan(new ForegroundColorSpan(text), start, label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (option.detail != null) {
            label.append('\n');
            start = label.length();
            label.append(option.detail);
            label.setSpan(new ForegroundColorSpan(secondary), start, label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            label.setSpan(new AbsoluteSizeSpan(15, true), start, label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        TextView view = new TextView(this);
        view.setText(label);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        view.setLineSpacing(0, 1.15f);
        view.setBackgroundResource(R.drawable.ts_option_bg);
        view.setPadding(dp(22), dp(14), dp(22), dp(14));
        view.setFocusable(true);
        view.setClickable(true);
        view.setOnClickListener(v -> choose(option.value));

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(12);
        view.setLayoutParams(params);
        return view;
    }

    // ------------------------------------------------------------------ steps

    private void showStep() {
        Step step = steps.get(stepIndex);
        List<Option> options = new ArrayList<>();
        String recommended;

        progressView.setText(getString(R.string.setup_progress, stepIndex + 1, steps.size()));

        switch (step) {
            case SCREEN: {
                titleView.setText(R.string.setup_screen_title);
                bodyView.setText(R.string.setup_screen_body);
                if (maxWidth >= 3840 && maxHeight >= 2160) {
                    options.add(new Option("3840x2160", getString(R.string.setup_res_4k), getString(R.string.setup_res_4k_detail)));
                }
                if (maxWidth >= 2560 && maxHeight >= 1440 && maxWidth < 3840) {
                    options.add(new Option("2560x1440", getString(R.string.setup_res_1440), getString(R.string.setup_res_1440_detail)));
                }
                if (maxHeight >= 1080) {
                    options.add(new Option("1920x1080", getString(R.string.setup_res_1080), getString(R.string.setup_res_1080_detail)));
                }
                options.add(new Option("1280x720", getString(R.string.setup_res_720), getString(R.string.setup_res_720_detail)));
                if (wired() && maxWidth >= 3840 && maxHeight >= 2160) {
                    recommended = "3840x2160";
                } else {
                    recommended = maxHeight >= 1080 ? "1920x1080" : "1280x720";
                }
                showOptions(options, resolution != null ? resolution : recommended, recommended);
                break;
            }
            case FRAME_RATE: {
                titleView.setText(R.string.setup_fps_title);
                bodyView.setText(R.string.setup_fps_body);
                options.add(new Option("120", getString(R.string.setup_fps_120), getString(R.string.setup_fps_120_detail)));
                options.add(new Option("60", getString(R.string.setup_fps_60), getString(R.string.setup_fps_60_detail)));
                recommended = wired() || "wifi6".equals(connection) ? "120" : "60";
                showOptions(options, fps != null ? fps : recommended, recommended);
                break;
            }
            case PICTURE: {
                titleView.setText(R.string.setup_picture_title);
                bodyView.setText(getString(R.string.setup_picture_body, connectionSentence()));
                options.add(new Option("smooth", getString(R.string.setup_pic_smooth),
                        getString(R.string.setup_pic_smooth_detail, bitrateKbps("smooth") / 1000)));
                options.add(new Option("balanced", getString(R.string.setup_pic_balanced),
                        getString(R.string.setup_pic_balanced_detail, bitrateKbps("balanced") / 1000)));
                options.add(new Option("sharp", getString(R.string.setup_pic_sharp),
                        getString(R.string.setup_pic_sharp_detail, bitrateKbps("sharp") / 1000)));
                recommended = "wifi24".equals(connection) ? "smooth" : "balanced";
                showOptions(options, picture != null ? picture : recommended, recommended);
                break;
            }
            case CONTROLLER: {
                titleView.setText(R.string.setup_controller_title);
                bodyView.setText(detectedControllerName != null
                        ? getString(R.string.setup_controller_found, detectedControllerName)
                        : getString(R.string.setup_controller_body));
                options.add(new Option("8bitdo", getString(R.string.setup_pad_8bitdo), getString(R.string.setup_pad_8bitdo_detail)));
                options.add(new Option("xbox", getString(R.string.setup_pad_xbox), getString(R.string.setup_pad_xbox_detail)));
                options.add(new Option("playstation", getString(R.string.setup_pad_playstation), getString(R.string.setup_pad_playstation_detail)));
                options.add(new Option("switch", getString(R.string.setup_pad_switch), getString(R.string.setup_pad_switch_detail)));
                options.add(new Option("other", getString(R.string.setup_pad_other), getString(R.string.setup_pad_other_detail)));
                recommended = detectedController;
                showOptions(options, controller != null ? controller : (recommended != null ? recommended : "8bitdo"), recommended);
                break;
            }
            case SOUND: {
                titleView.setText(R.string.setup_sound_title);
                bodyView.setText(R.string.setup_sound_body);
                options.add(new Option("2", getString(R.string.setup_sound_stereo), getString(R.string.setup_sound_stereo_detail)));
                options.add(new Option("51", getString(R.string.setup_sound_51), getString(R.string.setup_sound_51_detail)));
                options.add(new Option("71", getString(R.string.setup_sound_71), getString(R.string.setup_sound_71_detail)));
                showOptions(options, audio != null ? audio : "2", "2");
                break;
            }
            case DONE: {
                titleView.setText(R.string.setup_done_title);
                bodyView.setText(getString(R.string.setup_done_body,
                        resolutionName(), fps, bitrateKbps(picture) / 1000, controllerName(), soundName()));
                options.add(new Option("finish", getString(R.string.setup_done_start), getString(R.string.setup_done_start_detail)));
                options.add(new Option("again", getString(R.string.setup_done_again), getString(R.string.setup_done_again_detail)));
                showOptions(options, "finish", null);
                break;
            }
        }
    }

    /** Lists the answers and puts the controller's cursor on the current (or recommended) one. */
    private void showOptions(List<Option> options, String focus, String recommended) {
        optionsView.removeAllViews();
        View focused = null;
        for (Option option : options) {
            View view = makeOption(option, option.value.equals(recommended));
            optionsView.addView(view);
            if (option.value.equals(focus)) {
                focused = view;
            }
        }
        if (focused == null && optionsView.getChildCount() > 0) {
            focused = optionsView.getChildAt(0);
        }
        if (focused != null) {
            focused.requestFocus();
        }
    }

    private void choose(String value) {
        switch (steps.get(stepIndex)) {
            case SCREEN:
                resolution = value;
                break;
            case FRAME_RATE:
                fps = value;
                break;
            case PICTURE:
                picture = value;
                break;
            case CONTROLLER:
                controller = value;
                break;
            case SOUND:
                audio = value;
                break;
            case DONE:
                if ("again".equals(value)) {
                    stepIndex = 0;
                    showStep();
                } else {
                    save();
                    finish();
                }
                return;
        }
        if (fps == null && !supports120) {
            fps = "60";
        }
        stepIndex++;
        showStep();
    }

    // ------------------------------------------------------------------ results

    /** The PC sends this much data each second, in kilobits. */
    private int bitrateKbps(String choice) {
        String res = resolution != null ? resolution : "1920x1080";
        String rate = fps != null ? fps : "60";
        int base = PreferenceConfiguration.getDefaultBitrate(res, rate);
        double factor;
        if ("smooth".equals(choice)) {
            factor = 0.6;
        } else if ("sharp".equals(choice)) {
            factor = wired() ? 2.0 : 1.5;
        } else {
            factor = wired() ? 1.25 : 1.0;
        }
        // Whole megabits, never below 5
        return Math.max(5, (int) Math.round(base * factor / 1000.0)) * 1000;
    }

    private String connectionSentence() {
        switch (connection) {
            case "ethernet":
                return getString(R.string.setup_net_ethernet);
            case "wifi6":
                return getString(R.string.setup_net_wifi6);
            case "wifi5":
                return getString(R.string.setup_net_wifi5);
            case "wifi24":
                return getString(R.string.setup_net_wifi24);
            case "wifi":
                return getString(R.string.setup_net_wifi);
            default:
                return getString(R.string.setup_net_unknown);
        }
    }

    private String resolutionName() {
        switch (resolution) {
            case "3840x2160":
                return getString(R.string.setup_res_4k);
            case "2560x1440":
                return getString(R.string.setup_res_1440);
            case "1280x720":
                return getString(R.string.setup_res_720);
            default:
                return getString(R.string.setup_res_1080);
        }
    }

    private String controllerName() {
        switch (controller) {
            case "8bitdo":
                return getString(R.string.setup_pad_8bitdo);
            case "xbox":
                return getString(R.string.setup_pad_xbox);
            case "playstation":
                return getString(R.string.setup_pad_playstation);
            case "switch":
                return getString(R.string.setup_pad_switch);
            default:
                return getString(R.string.setup_pad_other);
        }
    }

    private String soundName() {
        switch (audio) {
            case "51":
                return getString(R.string.setup_sound_51);
            case "71":
                return getString(R.string.setup_sound_71);
            default:
                return getString(R.string.setup_sound_stereo_detail).replace(".", "");
        }
    }

    private void save() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        SharedPreferences.Editor edit = prefs.edit()
                .putString(PreferenceConfiguration.RESOLUTION_PREF_STRING, resolution)
                .putString(PreferenceConfiguration.FPS_PREF_STRING, fps)
                .putInt(PreferenceConfiguration.BITRATE_PREF_STRING, bitrateKbps(picture))
                .putString(PreferenceConfiguration.AUDIO_CONFIG_PREF_STRING, audio)
                .putBoolean("checkbox_use_virtual_display", true)
                .putBoolean("checkbox_enable_rumble", true)
                .putBoolean("checkbox_multi_controller", true)
                .putString(CONTROLLER_PREF, controller)
                .putString(PICTURE_PREF, picture)
                .putBoolean(SETUP_COMPLETE_PREF, true);
        if ("playstation".equals(controller)) {
            edit.putBoolean("checkbox_gamepad_motion_sensors", true);
        }
        edit.apply();
    }

    /** Opens setup from Settings. */
    public static void launch(Context context) {
        context.startActivity(new Intent(context, SetupActivity.class));
    }
}
