package app.quietdns;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicBoolean;

public final class QuietApp extends Application {
    enum State { OFF, STARTING, ON }
    final Handler main = new Handler(Looper.getMainLooper());
    final CopyOnWriteArraySet<Runnable> observers = new CopyOnWriteArraySet<>();
    private final AtomicBoolean updatePosted = new AtomicBoolean();
    volatile State state = State.OFF;
    volatile String error = "";
    Rules rules;
    ActivityLog activity;

    @Override public void onCreate() {
        super.onCreate();
        activity = new ActivityLog(this);
        rules = new Rules(this);
    }

    void changed() {
        if (!observers.isEmpty() && updatePosted.compareAndSet(false, true)) {
            main.postDelayed(() -> {
                updatePosted.set(false);
                for (Runnable observer : observers) observer.run();
            }, 220);
        }
    }
}
