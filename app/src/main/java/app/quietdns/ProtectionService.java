package app.quietdns;

import android.app.*;
import android.content.Intent;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import android.system.OsConstants;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import engine.Engine;
import engine.Session;

public final class ProtectionService extends VpnService {
    static final String START = "app.quietdns.START", STOP = "app.quietdns.STOP";
    private static final String DNS = "192.0.2.53", CHANNEL = "protection";
    private static final int NOTICE = 41;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private QuietApp app;
    private volatile int generation;
    private NetworkResolver resolver;
    private Session session;

    @Override public void onCreate() {
        super.onCreate();
        app = (QuietApp) getApplication();
        NotificationChannel channel = new NotificationChannel(CHANNEL, "DNS protection",
                NotificationManager.IMPORTANCE_LOW);
        channel.setShowBadge(false);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (STOP.equals(action) || (intent == null && !getPreferencesEnabled() && !isAlwaysOn())) {
            stopProtection("");
            return START_NOT_STICKY;
        }
        startForeground(NOTICE, notification(app.state == QuietApp.State.ON));
        if (app.state != QuietApp.State.OFF) return START_STICKY;
        final int token = ++generation;
        app.error = "";
        app.state = QuietApp.State.STARTING;
        app.changed();
        NetworkResolver next = new NetworkResolver(app, () -> {
            if (generation == token) stopProtection("Android closed the VPN connection.");
        });
        resolver = next;
        worker.execute(() -> connect(token, next));
        return START_STICKY;
    }

    private void connect(int token, NetworkResolver next) {
        try {
            app.rules.awaitFilter();
            if (generation != token) return;
            Builder builder = new Builder().setSession("QuietDNS").setMtu(1500)
                    .addAddress("192.0.2.1", 32).addDnsServer(DNS).addRoute(DNS, 32)
                    .allowFamily(OsConstants.AF_INET6).setBlocking(false).setMetered(false)
                    .setConfigureIntent(openApp());
            builder.addDisallowedApplication(getPackageName());
            try (ParcelFileDescriptor descriptor = builder.establish()) {
                if (descriptor == null) throw new IllegalStateException("VPN permission is needed.");
                // Load the library before handing ownership of the descriptor to Go.
                Engine.touch();
                Session connected = Engine.start(descriptor.detachFd(), DNS, next);
                app.main.post(() -> {
                    if (generation != token) { connected.close(); return; }
                    session = connected;
                    app.state = QuietApp.State.ON;
                    getSharedPreferences("service", 0).edit().putBoolean("enabled", true).apply();
                    getSystemService(NotificationManager.class).notify(NOTICE, notification(true));
                    app.changed();
                });
            }
        } catch (Exception | LinkageError e) {
            app.main.post(() -> {
                if (generation == token) stopProtection("Couldn’t start protection. Check VPN access and try again.");
            });
        }
    }

    private boolean getPreferencesEnabled() {
        return getSharedPreferences("service", 0).getBoolean("enabled", false);
    }

    private void disconnect() {
        generation++;
        if (resolver != null) { resolver.close(); resolver = null; }
        if (session != null) { session.close(); session = null; }
        app.state = QuietApp.State.OFF;
        app.activity.flush();
        app.changed();
    }

    private void stopProtection(String message) {
        app.error = message;
        getSharedPreferences("service", 0).edit().putBoolean("enabled", false).apply();
        disconnect();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override public void onRevoke() {
        app.main.post(() -> stopProtection("Android disconnected QuietDNS."));
    }

    @Override public void onDestroy() {
        disconnect();
        worker.shutdown();
        super.onDestroy();
    }

    private PendingIntent openApp() {
        return PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private Notification notification(boolean on) {
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, ProtectionService.class).setAction(STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(on ? "DNS protection is on" : "Starting DNS protection")
                .setContentText("QuietDNS · Local ad and tracker filtering").setContentIntent(openApp())
                .setShowWhen(false).setOnlyAlertOnce(true).setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "Pause", stop).build()).build();
    }
}
