package tech.eskey.tvpointer;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Insets;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import java.text.Collator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** D-pad friendly settings screen: service status, toggle button, speed/size and the app list. */
public final class SettingsActivity extends Activity {

    private Prefs prefs;
    private TextView status;
    private Button toggleKeyButton, speedButton, sizeButton, preciseButton;
    private CheckBox allApps;
    private ViewGroup appsContainer;
    private boolean capturingKey;
    /** OnBackInvokedCallback while choosing the toggle button (Android 13+). */
    private Object backCallback;

    private static final class AppEntry {
        final String pkg;
        final CharSequence label;
        final Drawable icon;

        AppEntry(String pkg, CharSequence label, Drawable icon) {
            this.pkg = pkg;
            this.label = label;
            this.icon = icon;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        prefs = new Prefs(this);
        if (Build.VERSION.SDK_INT >= 35) applyEdgeToEdgeInsets(findViewById(R.id.content));

        status = findViewById(R.id.status);
        toggleKeyButton = findViewById(R.id.toggle_key);
        speedButton = findViewById(R.id.speed);
        sizeButton = findViewById(R.id.size);
        allApps = findViewById(R.id.all_apps);
        appsContainer = findViewById(R.id.apps);

        findViewById(R.id.open_accessibility).setOnClickListener(v -> openAccessibilitySettings());
        toggleKeyButton.setOnClickListener(v -> startCapture());
        speedButton.setOnClickListener(v -> {
            prefs.setSpeed((prefs.speed() + 1) % 3);
            updateLabels();
        });
        sizeButton.setOnClickListener(v -> {
            prefs.setSize((prefs.size() + 1) % 3);
            updateLabels();
        });
        preciseButton = findViewById(R.id.precise);
        preciseButton.setOnClickListener(v -> togglePreciseTaps());
        allApps.setChecked(prefs.allApps());
        allApps.setOnCheckedChangeListener((b, checked) -> {
            prefs.setAllApps(checked);
            updateAppRows();
        });

        loadApps();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Enabled-but-stopped happens when the TV blocks the app from starting in the background
        // (e.g. TCL's auto-start control after an update or reboot).
        boolean enabled = isServiceEnabled();
        status.setText(!enabled ? R.string.status_off
                : PointerService.running ? R.string.status_on : R.string.status_stopped);
        updateLabels();
    }

    /**
     * Android 15+ draws apps edge-to-edge; on phones/tablets keep the content clear of the status
     * and navigation bars and any cutout (TVs report no insets, so nothing changes there).
     */
    @TargetApi(35)
    private static void applyEdgeToEdgeInsets(View content) {
        int left = content.getPaddingLeft(), top = content.getPaddingTop();
        int right = content.getPaddingRight(), bottom = content.getPaddingBottom();
        content.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            v.setPadding(left + bars.left, top + bars.top, right + bars.right, bottom + bars.bottom);
            return insets;
        });
    }

    // ---------------------------------------------------------------- choosing the toggle button

    private void startCapture() {
        capturingKey = true;
        toggleKeyButton.setText(R.string.toggle_key_waiting);
        if (Build.VERSION.SDK_INT >= 33) BackCompat.register(this);
    }

    private void stopCapture() {
        capturingKey = false;
        if (Build.VERSION.SDK_INT >= 33) BackCompat.unregister(this);
        updateLabels();
    }

    /**
     * Back on Android 8–12, which has no OnBackInvokedCallback: cancel choosing the toggle button
     * instead of leaving. Android 13+ uses BackCompat (the activity opts in via the manifest).
     */
    @Override
    @SuppressLint("GestureBackNavigation")
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (capturingKey) stopCapture();
        else super.onBackPressed();
    }

    /** Holds API 33 types away from SettingsActivity's own fields, so it loads on Android 8–12. */
    @TargetApi(33)
    private static final class BackCompat {
        static void register(SettingsActivity a) {
            unregister(a);
            OnBackInvokedCallback callback = a::stopCapture;
            a.backCallback = callback;
            a.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback);
        }

        static void unregister(SettingsActivity a) {
            if (a.backCallback == null) return;
            a.getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback((OnBackInvokedCallback) a.backCallback);
            a.backCallback = null;
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        if (!capturingKey || !isAssignable(code)) return super.dispatchKeyEvent(event);
        if (event.getAction() == KeyEvent.ACTION_UP) {
            prefs.setToggleKey(code);
            stopCapture();
        }
        return true;
    }

    /**
     * Keys the pointer itself needs, that the system owns, or that apps rely on for going back or
     * typing can't be the toggle button (the service swallows it in pointer apps).
     */
    private static boolean isAssignable(int code) {
        if (code >= KeyEvent.KEYCODE_A && code <= KeyEvent.KEYCODE_Z) return false;
        switch (code) {
            case KeyEvent.KEYCODE_ESCAPE:
            case KeyEvent.KEYCODE_BUTTON_B:
            case KeyEvent.KEYCODE_DEL:
            case KeyEvent.KEYCODE_FORWARD_DEL:
            case KeyEvent.KEYCODE_SPACE:
            case KeyEvent.KEYCODE_TAB:
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_HOME:
            case KeyEvent.KEYCODE_POWER:
            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_VOLUME_DOWN:
            case KeyEvent.KEYCODE_VOLUME_MUTE:
                return false;
            default:
                return true;
        }
    }

    /** Turning on connects right away, so the TV's "Allow debugging?" prompt appears while the user expects it. */
    private void togglePreciseTaps() {
        if (prefs.preciseTaps()) {
            prefs.setPreciseTaps(false);
            updateLabels();
            return;
        }
        preciseButton.setText(R.string.precise_connecting);
        preciseButton.setEnabled(false);
        PreciseTaps.get(this).setUp(ok -> {
            if (isDestroyed()) return;
            preciseButton.setEnabled(true);
            prefs.setPreciseTaps(ok);
            if (!ok) Toast.makeText(this, R.string.precise_failed, Toast.LENGTH_LONG).show();
            updateLabels();
        });
    }

    private void updateLabels() {
        if (preciseButton.isEnabled()) {
            preciseButton.setText(prefs.preciseTaps() ? R.string.precise_on : R.string.precise_off);
        }
        if (!capturingKey) toggleKeyButton.setText(getString(R.string.toggle_key, keyName(prefs.toggleKey())));
        int[] speeds = { R.string.level_low, R.string.level_mid, R.string.level_high };
        int[] sizes = { R.string.size_low, R.string.size_mid, R.string.size_high };
        speedButton.setText(getString(R.string.speed, getString(speeds[prefs.speed()])));
        sizeButton.setText(getString(R.string.size, getString(sizes[prefs.size()])));
    }

    private static String keyName(int code) {
        String name = KeyEvent.keyCodeToString(code);
        if (name.startsWith("KEYCODE_")) name = name.substring(8);
        StringBuilder out = new StringBuilder();
        for (String word : name.split("_")) {
            if (word.isEmpty()) continue;
            if (out.length() > 0) out.append(' ');
            out.append(word.charAt(0)).append(word.substring(1).toLowerCase(Locale.ROOT));
        }
        return out.toString();
    }

    private void openAccessibilitySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.accessibility_missing, Toast.LENGTH_LONG).show();
        }
    }

    private boolean isServiceEnabled() {
        String enabled = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (TextUtils.isEmpty(enabled)) return false;
        ComponentName me = new ComponentName(this, PointerService.class);
        for (String s : enabled.split(":")) {
            ComponentName c = ComponentName.unflattenFromString(s);
            if (me.equals(c)) return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- app list

    private void loadApps() {
        new Thread(() -> {
            PackageManager pm = getPackageManager();
            Map<String, ResolveInfo> byPkg = new LinkedHashMap<>();
            for (String category : new String[] { Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER }) {
                Intent main = new Intent(Intent.ACTION_MAIN).addCategory(category);
                for (ResolveInfo ri : pm.queryIntentActivities(main, 0)) {
                    byPkg.putIfAbsent(ri.activityInfo.packageName, ri);
                }
            }
            byPkg.remove(getPackageName());
            List<AppEntry> entries = new ArrayList<>();
            for (Map.Entry<String, ResolveInfo> e : byPkg.entrySet()) {
                entries.add(new AppEntry(e.getKey(), e.getValue().loadLabel(pm), e.getValue().loadIcon(pm)));
            }
            Collator collator = Collator.getInstance();
            entries.sort((a, b) -> collator.compare(a.label.toString(), b.label.toString()));
            runOnUiThread(() -> showApps(entries));
        }, "load-apps").start();
    }

    private void showApps(List<AppEntry> entries) {
        if (isDestroyed()) return;
        LayoutInflater inflater = getLayoutInflater();
        appsContainer.removeAllViews();
        for (AppEntry entry : entries) {
            View row = inflater.inflate(R.layout.app_row, appsContainer, false);
            ((ImageView) row.findViewById(R.id.icon)).setImageDrawable(entry.icon);
            ((TextView) row.findViewById(R.id.label)).setText(entry.label);
            row.setTag(entry.pkg);
            View toggle = row.findViewById(R.id.toggle);
            toggle.setOnClickListener(v -> {
                CheckBox check = v.findViewById(R.id.check);
                prefs.setApp(entry.pkg, !check.isChecked());
                updateAppRows();
            });
            row.findViewById(R.id.open).setOnClickListener(v -> openApp(entry.pkg));
            appsContainer.addView(row);
        }
        updateAppRows();
    }

    /** Prefers the app's TV entry point, falling back to its phone launcher activity. */
    private void openApp(String pkg) {
        PackageManager pm = getPackageManager();
        Intent launch = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (launch == null) launch = pm.getLaunchIntentForPackage(pkg);
        try {
            if (launch == null) throw new ActivityNotFoundException();
            startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.open_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void updateAppRows() {
        boolean all = prefs.allApps();
        Set<String> enabled = prefs.apps();
        for (int i = 0; i < appsContainer.getChildCount(); i++) {
            View row = appsContainer.getChildAt(i);
            View toggle = row.findViewById(R.id.toggle);
            CheckBox check = row.findViewById(R.id.check);
            check.setChecked(all || enabled.contains((String) row.getTag()));
            // With "all apps" on, the checkboxes are moot but Open still works.
            toggle.setEnabled(!all);
            toggle.setFocusable(!all);
            toggle.setAlpha(all ? 0.5f : 1f);
        }
    }
}
