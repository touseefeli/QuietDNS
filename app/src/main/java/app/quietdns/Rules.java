package app.quietdns;

import android.content.SharedPreferences;
import android.util.AtomicFile;
import java.io.*;
import java.net.IDN;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;
import javax.net.ssl.HttpsURLConnection;

final class Rules {
    static final String SOURCE = "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/light-onlydomains.txt";
    private static final int MAX_BYTES = 8 * 1024 * 1024;
    private static final Pattern LIST_NAME = Pattern.compile("[a-z0-9_-]+(?:\\.[a-z0-9_-]+)+");
    static final class Filter {
        final Set<String> domains;
        final String version, modified;
        Filter(Set<String> domains, String version, String modified) {
            this.domains = domains; this.version = version; this.modified = modified;
        }
    }
    private static final class Personal {
        final Set<String> allow, block;
        Personal(Set<String> allow, Set<String> block) {
            this.allow = Collections.unmodifiableSet(new HashSet<>(allow)); this.block = Collections.unmodifiableSet(new HashSet<>(block));
        }
    }
    private final QuietApp app;
    private final SharedPreferences prefs;
    private final AtomicFile cache;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Future<?> initial;
    volatile Filter filter;
    private volatile Personal personal;
    volatile boolean updating;
    volatile String updateMessage = "";

    Rules(QuietApp app) {
        this.app = app;
        prefs = app.getSharedPreferences("rules", 0);
        cache = new AtomicFile(new File(app.getFilesDir(), "light.txt"));
        personal = new Personal(prefs.getStringSet("allow", Collections.emptySet()),
                prefs.getStringSet("block", Collections.emptySet()));
        initial = io.submit(() -> {
            try (InputStream input = cache.openRead()) {
                filter = parse(input);
            } catch (IOException missingOrInvalid) {
                try (InputStream input = app.getAssets().open("light.txt")) {
                    filter = parse(input);
                } catch (IOException broken) {
                    updateMessage = "The filter could not be loaded. Try updating it.";
                }
            }
            app.changed();
        });
    }

    void awaitFilter() throws Exception {
        initial.get();
        if (filter == null) throw new IOException("No filter is available. Update the list in Rules.");
    }

    boolean blocked(String domain) {
        Personal overrides = personal;
        if (matches(overrides.allow, domain)) return false;
        Filter current = filter;
        return matches(overrides.block, domain) || (current != null && matches(current.domains, domain));
    }

    private static boolean matches(Set<String> domains, String name) {
        if (domains.isEmpty()) return false;
        for (int start = 0; start < name.length();) {
            if (domains.contains(start == 0 ? name : name.substring(start))) return true;
            int dot = name.indexOf('.', start);
            if (dot == -1) break;
            start = dot + 1;
        }
        return false;
    }

    static String normalize(String value) {
        String input = value.trim();
        if (input.endsWith(".")) input = input.substring(0, input.length() - 1);
        String name = IDN.toASCII(input, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
        if (name.length() > 253 || !LIST_NAME.matcher(name).matches() || name.matches("[0-9.]+")) {
            throw new IllegalArgumentException("Enter a domain such as example.com.");
        }
        return name;
    }

    List<String> list(boolean allowed) {
        return new ArrayList<>(new TreeSet<>(allowed ? personal.allow : personal.block));
    }

    synchronized void change(String name, boolean allowed, boolean remove) {
        Set<String> allow = new HashSet<>(personal.allow);
        Set<String> block = new HashSet<>(personal.block);
        Set<String> selected = allowed ? allow : block;
        if (remove) selected.remove(name);
        else {
            selected.add(name);
            (allowed ? block : allow).remove(name);
        }
        prefs.edit().putStringSet("allow", allow).putStringSet("block", block).apply();
        personal = new Personal(allow, block);
        app.changed();
    }

    synchronized void update() {
        if (updating) return;
        updating = true;
        updateMessage = "";
        app.changed();
        io.execute(() -> {
            HttpsURLConnection conn = null;
            try {
                conn = (HttpsURLConnection) new URL(SOURCE).openConnection();
                conn.setConnectTimeout(12000);
                conn.setReadTimeout(18000);
                if (conn.getResponseCode() != 200) throw new IOException("Unavailable");
                byte[] bytes;
                try (InputStream input = conn.getInputStream()) { bytes = readBounded(input); }
                Filter next = parse(new ByteArrayInputStream(bytes));
                Filter old = filter;
                if (old == null || !next.version.equals(old.version)) {
                    FileOutputStream output = cache.startWrite();
                    try {
                        output.write(bytes);
                        cache.finishWrite(output);
                    } catch (IOException failure) {
                        cache.failWrite(output);
                        throw failure;
                    }
                    filter = next;
                    updateMessage = "Filter updated.";
                } else updateMessage = "Your filter is up to date.";
            } catch (IOException | RuntimeException e) {
                updateMessage = "Update unavailable. Check your connection and try again.";
            } finally {
                if (conn != null) conn.disconnect();
                updating = false;
                app.changed();
            }
        });
    }

    private static byte[] readBounded(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = input.read(buf)) != -1) {
            if (output.size() + n > MAX_BYTES) throw new IOException("Filter is too large");
            output.write(buf, 0, n);
        }
        return output.toByteArray();
    }

    private static Filter parse(InputStream input) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
        Set<String> domains = new HashSet<>(120000);
        String version = "", modified = "", line;
        int count = -1;
        while ((line = reader.readLine()) != null) {
            if (line.startsWith("# Number of entries: ")) {
                try { count = Integer.parseInt(line.substring(21).trim()); }
                catch (NumberFormatException e) { throw new IOException("Invalid entry count", e); }
            } else if (line.startsWith("# Version: ")) version = line.substring(11).trim();
            else if (line.startsWith("# Last modified: ")) modified = line.substring(17).trim();
            else if (!line.isEmpty() && !line.startsWith("#")) {
                if (line.length() > 253 || !LIST_NAME.matcher(line).matches()) throw new IOException("Invalid rule");
                domains.add(line);
            }
        }
        if (domains.size() != count || count < 1000 || version.isEmpty()) throw new IOException("Incomplete filter");
        return new Filter(domains, version, modified);
    }
}
