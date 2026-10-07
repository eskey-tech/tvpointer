package tech.eskey.tvpointer;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.KeyEvent;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** User settings shared by the settings screen and the pointer service. */
final class Prefs {
    static final String KEY_ALL_APPS = "all_apps";
    static final String KEY_APPS = "apps";
    static final String KEY_TOGGLE = "toggle_key";
    static final String KEY_SPEED = "speed";
    static final String KEY_SIZE = "size";
    static final String KEY_PRECISE = "precise_taps";

    /** Browsers are where a pointer is needed most, so they start enabled. */
    private static final Set<String> DEFAULT_APPS = new HashSet<>(Arrays.asList(
            "org.mozilla.firefox",
            "org.mozilla.firefox_beta",
            "org.mozilla.fenix",
            "org.mozilla.focus",
            "com.android.chrome",
            "com.chrome.beta",
            "com.microsoft.emmx",
            "com.brave.browser",
            "com.opera.browser",
            "com.kiwibrowser.browser",
            "com.vivaldi.browser",
            "com.duckduckgo.mobile.android",
            "com.sec.android.app.sbrowser"));

    static final float[] SPEED_FACTORS = { 0.65f, 1f, 1.5f };
    static final float[] SIZE_FACTORS = { 1.1f, 1.4f, 1.9f };

    final SharedPreferences sp;

    Prefs(Context context) {
        sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    boolean allApps() { return sp.getBoolean(KEY_ALL_APPS, false); }

    Set<String> apps() { return new HashSet<>(sp.getStringSet(KEY_APPS, DEFAULT_APPS)); }

    int toggleKey() { return sp.getInt(KEY_TOGGLE, KeyEvent.KEYCODE_MENU); }

    int speed() { return clampLevel(sp.getInt(KEY_SPEED, 1)); }

    int size() { return clampLevel(sp.getInt(KEY_SIZE, 1)); }

    boolean preciseTaps() { return sp.getBoolean(KEY_PRECISE, false); }

    void setPreciseTaps(boolean v) { sp.edit().putBoolean(KEY_PRECISE, v).apply(); }

    void setAllApps(boolean v) { sp.edit().putBoolean(KEY_ALL_APPS, v).apply(); }

    void setApp(String pkg, boolean enabled) {
        Set<String> apps = apps();
        if (enabled) apps.add(pkg); else apps.remove(pkg);
        sp.edit().putStringSet(KEY_APPS, apps).apply();
    }

    void setToggleKey(int keyCode) { sp.edit().putInt(KEY_TOGGLE, keyCode).apply(); }

    void setSpeed(int level) { sp.edit().putInt(KEY_SPEED, clampLevel(level)).apply(); }

    void setSize(int level) { sp.edit().putInt(KEY_SIZE, clampLevel(level)).apply(); }

    private static int clampLevel(int v) { return Math.max(0, Math.min(2, v)); }
}
