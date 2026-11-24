package app.quietdns;

import android.content.SharedPreferences;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

final class ActivityLog {
    static final class Entry {
        final String name;
        final long time;
        Entry(String name, long time) { this.name = name; this.time = time; }
    }
    private final QuietApp app;
    private final SharedPreferences prefs;
    private final ArrayDeque<Entry> recent = new ArrayDeque<>();
    private final Runnable save = this::flush;
    private boolean pendingSave;
    private String day;
    private long requests, blocked;

    ActivityLog(QuietApp app) {
        this.app = app;
        prefs = app.getSharedPreferences("counts", 0);
        day = prefs.getString("day", "");
        requests = prefs.getLong("requests", 0);
        blocked = prefs.getLong("blocked", 0);
        rollDay();
    }

    synchronized void record(String domain, boolean denied) {
        rollDay();
        requests++;
        if (denied) {
            blocked++;
            long now = System.currentTimeMillis();
            Entry first = recent.peekFirst();
            if (first == null || !domain.equals(first.name) || now - first.time > 2000) {
                recent.addFirst(new Entry(domain, now));
                if (recent.size() > 30) recent.removeLast();
            }
        }
        if (!pendingSave) {
            pendingSave = true;
            app.main.postDelayed(save, 3000);
        }
        app.changed();
    }

    synchronized long[] totals() {
        rollDay();
        return new long[] { blocked, requests };
    }

    synchronized List<Entry> recent() { return new ArrayList<>(recent); }

    synchronized void clearRecent() { recent.clear(); app.changed(); }

    synchronized void flush() {
        app.main.removeCallbacks(save);
        pendingSave = false;
        rollDay();
        prefs.edit().putString("day", day).putLong("requests", requests)
                .putLong("blocked", blocked).apply();
    }

    private void rollDay() {
        String today = LocalDate.now().toString();
        if (!today.equals(day)) {
            day = today;
            requests = 0;
            blocked = 0;
        }
    }
}
