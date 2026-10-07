package tech.eskey.tvpointer;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Point;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.Toast;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Turns the TV remote into a mouse pointer in the apps the user picked:
 *  - arrows move the pointer (accelerates while held)
 *  - OK taps at the pointer; holding OK long-presses
 *  - pushing the pointer against a screen edge hands the arrow key to the app, which scrolls
 *    (browsers) or moves focus (native lists)
 *  - the toggle key (Menu by default) pauses/resumes the pointer in the current app
 *  - Back with the on-screen keyboard open only closes the keyboard
 * In other apps every key passes through untouched, and while the on-screen keyboard is open
 * only Back is handled.
 */
public final class PointerService extends AccessibilityService
        implements Choreographer.FrameCallback, SharedPreferences.OnSharedPreferenceChangeListener {

    private static final int LEFT = 1, RIGHT = 2, UP = 4, DOWN = 8;
    private static final long HIDE_DELAY_MS = 6000;
    private static final long TAP_MS = 40;
    private static final long REPRESS_GRACE_NS = 250_000_000L;
    private static final float MIN_STEP_DP = 6;

    /** Whether the service is actually connected (it can be enabled in settings yet not running). */
    static volatile boolean running;

    private final Handler handler = new Handler(Looper.getMainLooper());
    /** Per key code: whether the current press of that key is ours (true) or the app's (false). */
    private final SparseBooleanArray ownedKeys = new SparseBooleanArray();
    /** Window id → package, learned from window events so key presses never have to ask the app. */
    private final SparseArray<String> windowPackages = new SparseArray<>();
    /** Pointer apps where the user paused the pointer with the toggle key, for this session. */
    private final Set<String> paused = new HashSet<>();
    private final Point screen = new Point();
    private final int[] screenOffset = new int[2];

    private Prefs prefs;
    private WindowManager wm;
    private PointerView view;
    private float density;
    private float speedFactor = 1;
    private boolean allApps;
    private Set<String> apps = Collections.emptySet();
    private int toggleKey;
    private boolean preciseTaps;

    private String foregroundPkg;
    private boolean imeShown;

    private float x = -1, y = -1;
    private boolean visible;
    private int dirMask;
    private long moveStartNs, releaseNs, lastFrameNs;
    private int releasedDir;
    private boolean frameScheduled;
    private boolean okDown, okSwallowed, longPressed;
    private boolean backOwned;
    /**
     * Injected taps need a touchscreen or "faketouch" device; Android refuses them on TVs that
     * declare neither (common), so there clicks go through the accessibility tree instead.
     */
    private boolean gesturesSupported;
    private int lastClickX, lastClickY;
    private boolean lastClickLong;
    private final Rect nodeBounds = new Rect();
    /** We have hidden the on-screen keyboard (SHOW_MODE_HIDDEN) after a Back press. */
    private boolean keyboardSuppressed;

    private final Runnable hideRunnable = new Runnable() {
        @Override public void run() { setVisible(false); }
    };
    private final Runnable longPressRunnable = new Runnable() {
        @Override public void run() {
            longPressed = true;
            click(true);
        }
    };
    private final Runnable refreshRunnable = new Runnable() {
        @Override public void run() { refreshForeground(); }
    };
    private final Runnable releaseKeyboardRunnable = new Runnable() {
        @Override public void run() { releaseKeyboardNow(); }
    };
    private final GestureResultCallback afterTap = new GestureResultCallback() {
        @Override public void onCompleted(GestureDescription g) {
            releaseKeyboard(400);
        }
        @Override public void onCancelled(GestureDescription g) {
            // The system refused the tap; press the element through the accessibility tree instead.
            clickNodeAt(lastClickX, lastClickY, lastClickLong);
        }
    };

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onServiceConnected() {
        density = getResources().getDisplayMetrics().density;
        PackageManager pm = getPackageManager();
        gesturesSupported = pm.hasSystemFeature(PackageManager.FEATURE_FAKETOUCH)
                || pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN);
        // If a previous instance died while the keyboard was suppressed, give it back.
        if (getSoftKeyboardController().getShowMode() == SHOW_MODE_HIDDEN) {
            getSoftKeyboardController().setShowMode(SHOW_MODE_AUTO);
        }
        prefs = new Prefs(this);
        prefs.sp.registerOnSharedPreferenceChangeListener(this);
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        view = new PointerView(this);
        view.setVisibility(View.GONE); // no overlay surface at all while the pointer is hidden
        loadPrefs();

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        wm.addView(view, lp);
        updateScreen();
        running = true;
    }

    @Override
    public void onDestroy() {
        running = false;
        hideNow();
        handler.removeCallbacksAndMessages(null);
        Choreographer.getInstance().removeFrameCallback(this);
        if (keyboardSuppressed) releaseKeyboardNow();
        if (prefs != null) prefs.sp.unregisterOnSharedPreferenceChangeListener(this);
        if (view != null && view.isAttachedToWindow()) wm.removeView(view);
        view = null;
        super.onDestroy();
    }

    @Override
    public void onInterrupt() {
        hideNow();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (view == null) return;
        int oldW = screen.x, oldH = screen.y;
        updateScreen();
        if (screen.x != oldW || screen.y != oldH) {
            // New resolution: stop and re-centre on the next press.
            hideNow();
            x = -1;
            y = -1;
        } else {
            clampToScreen();
        }
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sp, String key) {
        // A new app selection replaces any per-app pause made with the toggle key.
        if (Prefs.KEY_APPS.equals(key) || Prefs.KEY_ALL_APPS.equals(key)) paused.clear();
        loadPrefs();
    }

    private void loadPrefs() {
        allApps = prefs.allApps();
        apps = prefs.apps();
        toggleKey = prefs.toggleKey();
        preciseTaps = prefs.preciseTaps();
        speedFactor = Prefs.SPEED_FACTORS[prefs.speed()];
        view.setScale(Prefs.SIZE_FACTORS[prefs.size()]);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Content-changed and scrolled events are subscribed to only so that Android keeps this
        // service's node cache current (it updates the cache from the events a service receives);
        // otherwise a click can land on an element of a page that is no longer shown.
        int type = event.getEventType();
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && type != AccessibilityEvent.TYPE_WINDOWS_CHANGED) return;
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && event.getPackageName() != null && event.getWindowId() != -1) {
            windowPackages.put(event.getWindowId(), event.getPackageName().toString());
        }
        // Hide the pointer as soon as the user leaves the app or opens the keyboard.
        if (visible || keyboardSuppressed) {
            handler.removeCallbacks(refreshRunnable);
            handler.postDelayed(refreshRunnable, 120);
        }
    }

    // ---------------------------------------------------------------- keys

    @Override
    protected boolean onKeyEvent(KeyEvent e) {
        if (view == null) return false;
        int code = e.getKeyCode();
        int action = e.getAction();

        if (code == KeyEvent.KEYCODE_BACK) return onBackKey(e);

        boolean toggle = code == toggleKey;
        int dir = dirFor(code);
        if (dir == 0 && !toggle && !isClickKey(e)) return false;

        if (action == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
            refreshForeground();
            boolean own;
            if (toggle) {
                // Only in pointer apps; elsewhere the button keeps its normal job.
                own = !imeShown && pointerAppIgnoringPause();
            } else if (!pointerActive()) {
                own = false;
            } else if (dir != 0 && x >= 0 && atEdge(dir)) {
                // Already at the edge: let the app have the key so the page scrolls.
                own = false;
                if (visible) scheduleHide();
            } else {
                own = true;
            }
            ownedKeys.put(code, own);
            if (!own) return false;
            if (dir != 0) startMove(dir);
            else if (!toggle) okPressed();
            return true;
        }
        if (!ownedKeys.get(code, false)) return false;
        if (action == KeyEvent.ACTION_UP) {
            ownedKeys.delete(code);
            if (toggle) {
                if (!e.isCanceled()) togglePause();
            } else if (dir != 0) {
                stopMove(dir);
            } else {
                okReleased(e.isCanceled());
            }
        }
        return true; // auto-repeat of a key we own
    }

    /**
     * Back with the on-screen keyboard open (in a pointer app) only closes the keyboard, so the
     * pointer can reach search suggestions. Some TV keyboards don't handle Back themselves and
     * the app then closes its search screen instead.
     */
    private boolean onBackKey(KeyEvent e) {
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            if (e.getRepeatCount() > 0) return backOwned;
            refreshForeground();
            backOwned = imeShown && pointerActiveIgnoringIme() && !keyboardSuppressed
                    && getSoftKeyboardController().setShowMode(SHOW_MODE_HIDDEN);
            if (backOwned) {
                keyboardSuppressed = true;
                handler.removeCallbacks(releaseKeyboardRunnable);
            } else {
                // The app handles this Back (e.g. leaves search); give the keyboard back after it.
                releaseKeyboard(500);
            }
            return backOwned;
        }
        if (e.getAction() == KeyEvent.ACTION_UP && backOwned) {
            backOwned = false;
            return true;
        }
        return false;
    }

    /**
     * Ends keyboard suppression. Android remembers that the text field asked for the keyboard,
     * so this must wait until the user has acted: a tap (on the field it re-opens the keyboard,
     * on a suggestion the app closes the field first), leaving the app, or a Back the app handles.
     */
    private void releaseKeyboard(long delayMs) {
        if (!keyboardSuppressed) return;
        handler.removeCallbacks(releaseKeyboardRunnable);
        handler.postDelayed(releaseKeyboardRunnable, delayMs);
    }

    private void releaseKeyboardNow() {
        handler.removeCallbacks(releaseKeyboardRunnable);
        keyboardSuppressed = false;
        getSoftKeyboardController().setShowMode(SHOW_MODE_AUTO);
    }

    private static int dirFor(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT: return LEFT;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return RIGHT;
            case KeyEvent.KEYCODE_DPAD_UP: return UP;
            case KeyEvent.KEYCODE_DPAD_DOWN: return DOWN;
            default: return 0;
        }
    }

    private static int keyFor(int dir) {
        switch (dir) {
            case LEFT: return KeyEvent.KEYCODE_DPAD_LEFT;
            case RIGHT: return KeyEvent.KEYCODE_DPAD_RIGHT;
            case UP: return KeyEvent.KEYCODE_DPAD_UP;
            default: return KeyEvent.KEYCODE_DPAD_DOWN;
        }
    }

    private static boolean isClickKey(KeyEvent e) {
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
                return true;
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                // Leave Enter alone when it comes from a real keyboard.
                InputDevice d = e.getDevice();
                return d == null || d.getKeyboardType() != InputDevice.KEYBOARD_TYPE_ALPHABETIC;
            default:
                return false;
        }
    }

    // ---------------------------------------------------------------- where the pointer is active

    private boolean pointerActive() {
        return !imeShown && pointerActiveIgnoringIme();
    }

    private boolean pointerActiveIgnoringIme() {
        return pointerAppIgnoringPause() && !paused.contains(foregroundPkg);
    }

    /** The app in front is one the user picked for the pointer (or "all apps" is on). */
    private boolean pointerAppIgnoringPause() {
        String pkg = foregroundPkg;
        if (pkg == null || pkg.equals(getPackageName())) return false;
        return allApps || apps.contains(pkg);
    }

    /**
     * Finds the app in front and whether the on-screen keyboard is open. getWindows() is answered
     * by the system; the app itself is only asked (getRoot) for a window we have no event for yet.
     */
    private void refreshForeground() {
        handler.removeCallbacks(refreshRunnable);
        List<AccessibilityWindowInfo> windows;
        try {
            windows = getWindows();
        } catch (RuntimeException ex) {
            windows = Collections.emptyList();
        }
        boolean ime = false;
        AccessibilityWindowInfo app = null;
        Set<Integer> ids = new HashSet<>();
        for (AccessibilityWindowInfo w : windows) {
            ids.add(w.getId());
            int type = w.getType();
            if (type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                ime = true;
            } else if (type == AccessibilityWindowInfo.TYPE_APPLICATION) {
                if (w.isFocused()) app = w;
                else if (app == null && w.isActive()) app = w;
            }
        }
        for (int i = windowPackages.size() - 1; i >= 0; i--) {
            if (!ids.contains(windowPackages.keyAt(i))) windowPackages.removeAt(i);
        }

        String pkg = app == null ? null : windowPackages.get(app.getId());
        if (app != null && pkg == null) {
            AccessibilityNodeInfo root = app.getRoot();
            if (root != null && root.getPackageName() != null) {
                pkg = root.getPackageName().toString();
                windowPackages.put(app.getId(), pkg);
            }
        }
        if (pkg != null && !pkg.equals(foregroundPkg)) releaseKeyboard(300);
        if (pkg != null || app == null) foregroundPkg = pkg;
        imeShown = ime;
        if (!pointerActive()) hideNow();
    }

    private void togglePause() {
        String pkg = foregroundPkg;
        if (pkg == null) return;
        boolean nowPaused = !paused.remove(pkg);
        if (nowPaused) paused.add(pkg);
        if (pointerActive()) {
            ensurePosition();
            setVisible(true);
        } else {
            hideNow();
            releaseKeyboard(0); // pointer off means the remote works as normal again
        }
        Toast.makeText(this, nowPaused ? R.string.toast_off : R.string.toast_on, Toast.LENGTH_SHORT).show();
    }

    // ---------------------------------------------------------------- pointer state

    private void startMove(int dir) {
        long now = System.nanoTime();
        // Some IR remotes send down/up pairs instead of holding; keep accelerating across them.
        boolean fresh = dirMask == 0 && (now - releaseNs > REPRESS_GRACE_NS || dir != releasedDir);
        if (fresh) moveStartNs = now;
        dirMask |= dir;
        ensurePosition();
        if (fresh) {
            // Guaranteed small step, so even a very short tap moves the pointer.
            float nudge = MIN_STEP_DP * density;
            x += dir == RIGHT ? nudge : dir == LEFT ? -nudge : 0;
            y += dir == DOWN ? nudge : dir == UP ? -nudge : 0;
            clampToScreen();
        }
        setVisible(true);
        scheduleFrame();
    }

    private void stopMove(int dir) {
        dirMask &= ~dir;
        if (dirMask == 0) {
            releaseNs = System.nanoTime();
            releasedDir = dir;
        }
        scheduleHide();
    }

    private void okPressed() {
        if (!visible) {
            // First press after the pointer auto-hid only reveals it.
            okSwallowed = true;
            ensurePosition();
            setVisible(true);
            return;
        }
        okSwallowed = false;
        okDown = true;
        longPressed = false;
        handler.removeCallbacks(hideRunnable);
        handler.postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout());
        render();
    }

    private void okReleased(boolean canceled) {
        handler.removeCallbacks(longPressRunnable);
        if (okSwallowed) {
            okSwallowed = false;
        } else if (okDown && !longPressed && !canceled) {
            click(false);
        }
        okDown = false;
        render();
        scheduleHide();
    }

    /** Taps (or long-presses) at the pointer. */
    private void click(boolean longPress) {
        lastClickX = Math.round(x + screenOffset[0]);
        lastClickY = Math.round(y + screenOffset[1]);
        lastClickLong = longPress;
        if (!gesturesSupported) {
            final int cx = lastClickX, cy = lastClickY;
            if (preciseTaps) {
                // A real tap through the TV's debugging connection; the tree is the fallback.
                PreciseTaps.get(this).tap(cx, cy, longPress, ok -> {
                    if (ok) releaseKeyboard(400);
                    else clickNodeAt(cx, cy, longPress);
                });
            } else {
                // Don't block the key callback with tree lookups; the system times those out.
                handler.post(() -> clickNodeAt(cx, cy, longPress));
            }
            return;
        }
        Path path = new Path();
        path.moveTo(lastClickX, lastClickY);
        long duration = longPress ? ViewConfiguration.getLongPressTimeout() + 300L : TAP_MS;
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, duration))
                .build();
        dispatchGesture(gesture, afterTap, handler);
    }

    /**
     * Presses the element under (px, py): the deepest node there, or the nearest ancestor that
     * accepts the click (a link's text sits inside the clickable link, for example).
     */
    private void clickNodeAt(int px, int py, boolean longPress) {
        int action = longPress ? AccessibilityNodeInfo.ACTION_LONG_CLICK : AccessibilityNodeInfo.ACTION_CLICK;
        // Read the current screen, never a cached copy of an earlier page.
        if (Build.VERSION.SDK_INT >= 33) clearCache();
        List<AccessibilityWindowInfo> windows;
        try {
            windows = getWindows(); // top-most first
        } catch (RuntimeException ex) {
            windows = Collections.emptyList();
        }
        for (AccessibilityWindowInfo w : windows) {
            int type = w.getType();
            if (type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue;
            w.getBoundsInScreen(nodeBounds);
            if (!nodeBounds.contains(px, py)) continue;
            AccessibilityNodeInfo root = w.getRoot();
            if (root == null) continue;
            for (AccessibilityNodeInfo n = deepestNodeAt(root, px, py); n != null; n = n.getParent()) {
                if (n.isEnabled() && supports(n, action)) {
                    // refresh() fails if the element has gone in the meantime: then don't press it.
                    if (n.refresh()) n.performAction(action);
                    break;
                }
            }
            break; // only the top-most window under the pointer can receive the click
        }
        releaseKeyboard(400);
    }

    private AccessibilityNodeInfo deepestNodeAt(AccessibilityNodeInfo node, int px, int py) {
        // Later children are drawn on top, so search them first.
        for (int i = node.getChildCount() - 1; i >= 0; i--) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null || !child.isVisibleToUser()) continue;
            child.getBoundsInScreen(nodeBounds);
            if (nodeBounds.contains(px, py)) return deepestNodeAt(child, px, py);
        }
        return node;
    }

    private static boolean supports(AccessibilityNodeInfo n, int action) {
        if (action == AccessibilityNodeInfo.ACTION_CLICK && n.isClickable()) return true;
        if (action == AccessibilityNodeInfo.ACTION_LONG_CLICK && n.isLongClickable()) return true;
        for (AccessibilityNodeInfo.AccessibilityAction a : n.getActionList()) {
            if (a.getId() == action) return true;
        }
        return false;
    }

    private void ensurePosition() {
        updateScreen();
        if (x < 0) {
            x = screen.x / 2f;
            y = screen.y / 2f;
        }
        clampToScreen();
    }

    @SuppressWarnings("deprecation")
    private void updateScreen() {
        wm.getDefaultDisplay().getRealSize(screen);
        if (view.isLaidOut()) view.getLocationOnScreen(screenOffset);
    }

    private void clampToScreen() {
        x = clamp(x, 0, screen.x - 1);
        y = clamp(y, 0, screen.y - 1);
    }

    private boolean atEdge(int dir) {
        switch (dir) {
            case LEFT: return x <= 0;
            case RIGHT: return x >= screen.x - 1;
            case UP: return y <= 0;
            default: return y >= screen.y - 1;
        }
    }

    private void setVisible(boolean v) {
        visible = v;
        if (v) scheduleHide();
        render();
    }

    private void scheduleHide() {
        handler.removeCallbacks(hideRunnable);
        if (dirMask == 0 && !okDown) handler.postDelayed(hideRunnable, HIDE_DELAY_MS);
    }

    /** Stops movement, cancels a pending long press and hides the pointer. */
    private void hideNow() {
        dirMask = 0;
        okDown = false;
        okSwallowed = false;
        handler.removeCallbacks(longPressRunnable);
        handler.removeCallbacks(hideRunnable);
        visible = false;
        render();
    }

    private void render() {
        if (view == null) return;
        view.setVisibility(visible ? View.VISIBLE : View.GONE);
        view.update(x, y, visible, okDown);
    }

    // ---------------------------------------------------------------- per-frame movement

    private void scheduleFrame() {
        if (frameScheduled) return;
        frameScheduled = true;
        Choreographer.getInstance().postFrameCallback(this);
    }

    @Override
    public void doFrame(long frameNs) {
        frameScheduled = false;
        if (dirMask == 0 || view == null) {
            lastFrameNs = 0;
            return;
        }
        float dt = lastFrameNs == 0 ? 1 / 60f : clamp((frameNs - lastFrameNs) / 1e9f, 0, 0.05f);
        lastFrameNs = frameNs;
        int dx = ((dirMask & RIGHT) != 0 ? 1 : 0) - ((dirMask & LEFT) != 0 ? 1 : 0);
        int dy = ((dirMask & DOWN) != 0 ? 1 : 0) - ((dirMask & UP) != 0 ? 1 : 0);
        float step = pointerSpeed((frameNs - moveStartNs) / 1e9f) * speedFactor * density * dt;
        float nx = x + dx * step, ny = y + dy * step;
        x = nx;
        y = ny;
        clampToScreen();
        // Reaching an edge hands the held key to the app, so it scrolls the page instead.
        if (dx != 0 && x != nx) handToApp(dx > 0 ? RIGHT : LEFT);
        if (dy != 0 && y != ny) handToApp(dy > 0 ? DOWN : UP);
        render();
        if (dirMask != 0) scheduleFrame();
        else lastFrameNs = 0;
    }

    private void handToApp(int dir) {
        dirMask &= ~dir;
        int code = keyFor(dir);
        if (ownedKeys.indexOfKey(code) >= 0) ownedKeys.put(code, false);
        if (dirMask == 0) scheduleHide();
    }

    /** Pointer speed in dp/s after a key has been held for t seconds. */
    private static float pointerSpeed(float t) {
        if (t < 0.18f) return 160;
        float k = Math.min(1f, (t - 0.18f) / 0.9f);
        return 160 + 1340 * k * k;
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
