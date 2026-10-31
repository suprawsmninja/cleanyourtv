package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Date;

/**
 * tvhome fork (config.xml control_centre): the Settings pill opens a panel laid out like tvOS Control Centre, glass
 * tiles at the top right over whatever is behind. A focused tile turns white with dark content, as on Apple TV.
 */
public final class ControlCentreActivity extends Activity {
    private float dp;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LauncherLocale.wrap(base));
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        dp = getResources().getDisplayMetrics().density;

        FrameLayout root = new FrameLayout(this);
        // Dims the right side, where the panel is, and leaves the rest of the picture alone.
        GradientDrawable shade = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0x00000000, 0x40000000, 0xB3000000});
        root.setBackground(shade);
        root.setOnClickListener(v -> finish());
        setContentView(root);

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.END);
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        plp.setMargins(0, px(28), px(40), 0);
        root.addView(panel, plp);

        TextView clock = new TextView(this);
        clock.setText(DateFormat.getTimeFormat(this).format(new Date()));
        clock.setTextColor(0xFFFFFFFF);
        clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        clock.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        clock.setPadding(0, 0, px(6), px(16));
        panel.addView(clock, wrap());

        int gap = px(14), small = px(64), wide = px(180), big = small * 2 + gap;

        // Power, then Wi-Fi and Picture stacked beside it.
        LinearLayout top = row(panel);
        View power = tile(R.drawable.ic_cc_power, getString(R.string.cc_power), big, big, true, this::powerOff);
        top.addView(power);
        space(top, gap);
        LinearLayout stack = new LinearLayout(this);
        stack.setOrientation(LinearLayout.VERTICAL);
        top.addView(stack, wrap());
        stack.addView(tile(R.drawable.ic_wifi, wifiName(), wide, small, false, () -> open(SystemSettings.intent(this, Settings.ACTION_WIFI_SETTINGS))));
        spaceV(stack, gap);
        stack.addView(tile(R.drawable.ic_picture, getString(R.string.quick_picture), wide, small, false, () -> open(SystemSettings.intent(this, Settings.ACTION_DISPLAY_SETTINGS))));

        spaceV(panel, gap);
        LinearLayout mid = row(panel);
        mid.addView(tile(R.drawable.ic_cc_audio, getString(R.string.cc_audio), big, small, false, () -> open(SystemSettings.intent(this, Settings.ACTION_SOUND_SETTINGS))));
        space(mid, gap);
        mid.addView(tile(R.drawable.ic_cc_timer, getString(R.string.cc_sleep_timer), wide, small, false, () -> open(new Intent("com.sony.dtv.timers.intent.action.SLEEP_TIMER"))));

        spaceV(panel, gap);
        LinearLayout bottom = row(panel);
        int round = small;
        bottom.addView(roundTile(R.drawable.ic_input, R.string.inputs, round, () -> open(new Intent(this, MainActivity.class).setAction(MainActivity.ACTION_VIEW_INPUTS))));
        space(bottom, gap);
        bottom.addView(roundTile(R.drawable.ic_cc_access, R.string.cc_accessibility, round, () -> open(SystemSettings.intent(this, Settings.ACTION_ACCESSIBILITY_SETTINGS))));
        space(bottom, gap);
        bottom.addView(roundTile(R.drawable.ic_settings, R.string.tv_settings, round, () -> open(SystemSettings.intent(this, Settings.ACTION_SETTINGS))));
        space(bottom, gap);
        bottom.addView(roundTile(R.drawable.ic_home_settings, R.string.launcher_settings, round, () -> open(
                new Intent(this, MainActivity.class).setAction(MainActivity.ACTION_LAUNCHER_SETTINGS))));

        power.requestFocus();
    }

    /** Anything else taking the screen closes the panel, as Back does. */
    @Override
    protected void onPause() {
        super.onPause();
        finish();
        overridePendingTransition(0, 0);
    }

    private void powerOff() {
        if (HomeKeyService.sleep()) {
            finish();
            return;
        }
        // Without the Home key helper a launcher cannot put the TV to sleep; Sony's timers page can.
        Toast.makeText(this, R.string.cc_power_needs_helper, Toast.LENGTH_LONG).show();
    }

    private void open(Intent target) {
        try {
            startActivity(target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException | NullPointerException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
        finish();
    }

    private String wifiName() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            WifiInfo info = wm != null ? wm.getConnectionInfo() : null;
            String ssid = info != null ? info.getSSID() : null;
            if (ssid != null) {
                ssid = ssid.replace("\"", "").trim();
                if (!ssid.isEmpty() && !ssid.contains("unknown") && !ssid.equals("0x")) return "Wi-Fi\n" + ssid;
            }
        } catch (RuntimeException ignored) {
        }
        return "Wi-Fi";
    }

    // ---- tiles ----------------------------------------------------------------------------------

    private View tile(int icon, String label, int w, int h, boolean vertical, Runnable action) {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(vertical ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        t.setGravity(Gravity.CENTER);
        t.setPadding(px(14), px(8), px(14), px(8));
        ImageView iv = new ImageView(this);
        iv.setImageResource(icon);
        int size = vertical ? px(56) : px(30);
        t.addView(iv, new LinearLayout.LayoutParams(size, size));
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, vertical ? 15 : 14);
        tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        tv.setGravity(vertical ? Gravity.CENTER : Gravity.START | Gravity.CENTER_VERTICAL);
        tv.setMaxLines(2);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(vertical ? ViewGroup.LayoutParams.WRAP_CONTENT : 0, ViewGroup.LayoutParams.WRAP_CONTENT, vertical ? 0 : 1);
        if (vertical) tlp.topMargin = px(10);
        else tlp.setMarginStart(px(12));
        t.addView(tv, tlp);
        glass(t, iv, tv, px(26), action);
        t.setLayoutParams(new LinearLayout.LayoutParams(w, h));
        return t;
    }

    private View roundTile(int icon, int description, int size, Runnable action) {
        FrameLayout t = new FrameLayout(this);
        ImageView iv = new ImageView(this);
        iv.setImageResource(icon);
        t.addView(iv, new FrameLayout.LayoutParams(px(28), px(28), Gravity.CENTER));
        t.setContentDescription(getString(description));
        glass(t, iv, null, size / 2, action);
        t.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        return t;
    }

    /** Frosted glass at rest; solid white with dark content when focused. */
    private void glass(View t, ImageView icon, TextView label, int radius, Runnable action) {
        GradientDrawable rest = new GradientDrawable();
        rest.setCornerRadius(radius);
        rest.setColor(0xE62C2C30);   // dark frosted glass: opaque enough that Home's top bar does not show through
        rest.setStroke(Math.max(1, px(1)), 0x33FFFFFF);
        GradientDrawable focus = new GradientDrawable();
        focus.setCornerRadius(radius);
        focus.setColor(0xFFFFFFFF);
        t.setBackground(rest);
        t.setFocusable(true);
        t.setClickable(true);
        icon.setColorFilter(0xFFFFFFFF);
        if (label != null) label.setTextColor(0xFFFFFFFF);
        t.setOnFocusChangeListener((v, focused) -> {
            v.setBackground(focused ? focus : rest);
            int ink = focused ? 0xFF1C1C1E : 0xFFFFFFFF;
            icon.setColorFilter(ink);
            if (label != null) label.setTextColor(ink);
            v.animate().scaleX(focused ? 1.06f : 1f).scaleY(focused ? 1.06f : 1f).setDuration(140).start();
        });
        t.setOnClickListener(v -> action.run());
    }

    private LinearLayout row(LinearLayout parent) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        parent.addView(r, wrap());
        return r;
    }

    private void space(LinearLayout parent, int w) {
        parent.addView(new View(this), new LinearLayout.LayoutParams(w, 1));
    }

    private void spaceV(LinearLayout parent, int h) {
        parent.addView(new View(this), new LinearLayout.LayoutParams(1, h));
    }

    /** A vertical LinearLayout's default width is match_parent, which collapses a wrap_content panel. */
    private static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int px(float v) {
        return Math.round(v * dp);
    }
}
