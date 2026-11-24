package app.quietdns;

import android.content.Context;
import android.net.*;
import android.os.CancellationSignal;
import java.util.Set;
import java.util.concurrent.*;
import engine.Resolver;

final class NetworkResolver implements Resolver {
    private final QuietApp app;
    private final ConnectivityManager networks;
    private final Runnable lost;
    private final Set<CancellationSignal> pending = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    NetworkResolver(QuietApp app, Runnable lost) {
        this.app = app;
        this.lost = lost;
        networks = (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
    }

    @Override public boolean blocked(String name) {
        if (closed) return true;
        boolean blocked = app.rules.blocked(name);
        app.activity.record(name, blocked);
        return blocked;
    }

    @Override public byte[] forward(byte[] query) {
        if (closed) return null;
        // This package is excluded from its own VPN. Ask Android for its current
        // physical network on every lookup so Wi-Fi/mobile handoffs work.
        Network physical = networks.getActiveNetwork();
        if (physical == null) return null;
        NetworkCapabilities caps = networks.getNetworkCapabilities(physical);
        if (caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) {
            return null;
        }
        CancellationSignal signal = new CancellationSignal();
        CompletableFuture<byte[]> result = new CompletableFuture<>();
        pending.add(signal);
        try {
            if (closed) return null;
            DnsResolver.getInstance().rawQuery(physical, query, DnsResolver.FLAG_EMPTY, Runnable::run,
                    signal, new DnsResolver.Callback<byte[]>() {
                        @Override public void onAnswer(byte[] answer, int rcode) { result.complete(answer); }
                        @Override public void onError(DnsResolver.DnsException error) { result.complete(null); }
                    });
            return result.get(7, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
            return null;
        } finally {
            pending.remove(signal);
            signal.cancel();
        }
    }

    @Override public void tunnelLost() {
        if (!closed) app.main.post(lost);
    }

    void close() {
        closed = true;
        for (CancellationSignal signal : pending) signal.cancel();
    }
}
