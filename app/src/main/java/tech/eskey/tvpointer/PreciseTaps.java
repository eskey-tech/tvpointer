package tech.eskey.tvpointer;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * "Precise taps": real taps sent through the TV's own network-debugging connection, for TVs
 * where Android refuses taps from accessibility services and the element under the pointer
 * can't be pressed through the accessibility tree. All ADB work runs on one background thread.
 */
final class PreciseTaps {
    interface Callback {
        void done(boolean ok);
    }

    @SuppressLint("StaticFieldLeak") // holds only the application context
    private static PreciseTaps instance;

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private AdbClient client;

    static synchronized PreciseTaps get(Context context) {
        if (instance == null) instance = new PreciseTaps(context.getApplicationContext());
        return instance;
    }

    private PreciseTaps(Context app) {
        this.app = app;
    }

    /** Connects for the first time; the TV asks the user to allow it. Callback runs on the main thread. */
    void setUp(Callback callback) {
        worker.execute(() -> {
            boolean ok = connect(true);
            main.post(() -> callback.done(ok));
        });
    }

    /** Taps (or long-presses) at screen coordinates. Callback runs on the main thread. */
    void tap(int x, int y, boolean longPress, Callback callback) {
        String command = longPress
                ? String.format(Locale.ROOT, "input swipe %d %d %d %d 800", x, y, x, y)
                : String.format(Locale.ROOT, "input tap %d %d", x, y);
        worker.execute(() -> {
            boolean ok = run(command);
            main.post(() -> callback.done(ok));
        });
    }

    private boolean run(String command) {
        // A kept-alive connection can go stale (adbd restart, TV standby): retry once on a fresh one.
        for (int attempt = 0; attempt < 2; attempt++) {
            if (!connect(false)) return false;
            try {
                client.shell(command);
                return true;
            } catch (IOException e) {
                client.close();
            }
        }
        return false;
    }

    private boolean connect(boolean allowPrompt) {
        try {
            if (client == null) client = new AdbClient(app);
            if (client.isConnected()) return true;
            return client.connect(allowPrompt, 60_000);
        } catch (IOException | GeneralSecurityException e) {
            if (client != null) client.close();
            return false;
        }
    }
}
