package app.quietdns;

import android.Manifest;
import android.app.*;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.VpnService;
import android.os.*;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.*;
import static app.quietdns.Graphics.*;

public final class MainActivity extends Activity {
    private enum Page { HOME, RULES, ACTIVITY, ALLOW, BLOCK }
    private static final int MATCH = -1, WRAP = -2;
    private static final int VPN_REQUEST = 20, NOTIFICATION_REQUEST = 21;
    private QuietApp app;
    private Typeface font;
    private Page page = Page.HOME;
    private LinearLayout root, body, nav;
    private ScrollView scroll;
    private TextView blockedCount, requestCount, latestName, latestTime;
    private ActivityLog.Entry recentHead;
    private Rules.Filter shownFilter;
    private QuietApp.State shownState;
    private String shownError, shownUpdate;
    private boolean shownUpdating;
    private final Runnable observer = this::update;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        app = (QuietApp) getApplication();
        font = getResources().getFont(R.font.dm_sans);
        if (saved != null) page = Page.valueOf(saved.getString("page", "HOME"));
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarContrastEnforced(false);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        root = col(); root.setBackgroundColor(PAPER);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        setContentView(root);
        buildHeader();
        scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false); scroll.setFillViewport(false);
        body = col(); pad(body, 20, 6, 20, 24);
        scroll.addView(body); root.addView(scroll, new LinearLayout.LayoutParams(MATCH, 0, 1));
        View border = new View(this); border.setBackgroundColor(LINE);
        root.addView(border, MATCH, 1);
        nav = row(); nav.setGravity(Gravity.CENTER); pad(nav, 7, 3, 7, 3);
        root.addView(nav, MATCH, WRAP);
        render(false);
    }

    @Override protected void onResume() { super.onResume(); app.observers.add(observer); render(true); }
    @Override protected void onPause() { app.observers.remove(observer); super.onPause(); }
    @Override protected void onSaveInstanceState(Bundle out) { super.onSaveInstanceState(out); out.putString("page", page.name()); }
    @Override public void onBackPressed() {
        if (page == Page.ALLOW || page == Page.BLOCK) open(Page.RULES);
        else if (page != Page.HOME) open(Page.HOME);
        else super.onBackPressed();
    }

    private void buildHeader() {
        LinearLayout bar = row(); pad(bar, 20, 10, 14, 10);
        Icon logo = icon("brand", LIME, 23); logo.setBackground(shape(FOREST, 10, 0));
        bar.addView(logo, dp(35), dp(35)); hgap(bar, 9);
        TextView name = txt("QuietDNS", 24, 760, INK); name.setLetterSpacing(-.055f);
        bar.addView(name, weight());
        Icon info = icon("info", MUTED, 23); info.setContentDescription("About QuietDNS and help");
        info.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        tap(info, SURFACE, 24, LINE, this::about);
        bar.addView(info, dp(45), dp(45)); root.addView(bar, MATCH, WRAP);
    }

    private void open(Page next) { page = next; render(false); }

    private void render(boolean keepScroll) {
        if (scroll == null) return;
        int oldY = keepScroll ? scroll.getScrollY() : 0;
        body.removeAllViews(); nav.removeAllViews();
        blockedCount = requestCount = null; recentHead = null;
        shownState = app.state; shownFilter = app.rules.filter; shownError = app.error;
        shownUpdate = app.rules.updateMessage; shownUpdating = app.rules.updating;
        switch (page) {
            case HOME: home(); break;
            case RULES: rules(); break;
            case ACTIVITY: activity(); break;
            case ALLOW: personal(true); break;
            case BLOCK: personal(false); break;
        }
        tab(Page.HOME, "home", "Home");
        tab(Page.RULES, "rules", "Rules");
        tab(Page.ACTIVITY, "activity", "Activity");
        scroll.post(() -> scroll.scrollTo(0, oldY));
    }

    private void update() {
        if (shownState != app.state || shownFilter != app.rules.filter || !Objects.equals(shownError, app.error)
                || (page == Page.RULES && (shownUpdating != app.rules.updating
                || !Objects.equals(shownUpdate, app.rules.updateMessage)))) {
            render(true); return;
        }
        if (page == Page.ACTIVITY || page == Page.HOME) {
            List<ActivityLog.Entry> items = app.activity.recent();
            ActivityLog.Entry latest = items.isEmpty() ? null : items.get(0);
            if (latest != recentHead) {
                if (page == Page.ACTIVITY) { render(true); return; }
                recentHead = latest;
                latestName.setText(latest == null ? "No recent blocks" : latest.name);
                latestTime.setText(latest == null ? "Blocked domains will appear here." : time(latest.time));
            }
            setCounts();
        }
    }

    private void tab(Page dest, String glyph, String label) {
        boolean selected = page == dest || (dest == Page.RULES && (page == Page.ALLOW || page == Page.BLOCK));
        LinearLayout item = col(); item.setGravity(Gravity.CENTER); pad(item, 0, 4, 0, 3);
        Icon i = icon(glyph, selected ? FOREST : MUTED, 22);
        if (selected) i.setBackground(shape(TINT, 16, 0));
        item.addView(i, dp(52), dp(29));
        TextView t = txt(label, 12, selected ? 730 : 550, selected ? FOREST : MUTED);
        t.setGravity(Gravity.CENTER);
        item.addView(t); item.setSelected(selected);
        tap(item, PAPER, 12, 0, () -> open(dest));
        nav.addView(item, new LinearLayout.LayoutParams(0, WRAP, 1));
    }

    private void home() {
        boolean on = shownState == QuietApp.State.ON, starting = shownState == QuietApp.State.STARTING;
        LinearLayout hero = col(); hero.setBackground(shape(FOREST, 25, 0)); hero.setClipToOutline(true);
        FrameLayout field = new FrameLayout(this);
        field.addView(new Contours(this, on), MATCH, MATCH);
        LinearLayout copy = col(); pad(copy, 23, 23, 10, 17);
        TextView kind = txt("DEVICE PROTECTION", 10, 750, 0xffc3d9c4); kind.setLetterSpacing(.14f);
        copy.addView(kind);
        TextView status = txt(on ? "Active" : starting ? "Starting…" : "Paused", 42, 650, 0xfff5f9eb);
        status.setLetterSpacing(-.055f); gap(copy, 3); copy.addView(status);
        gap(copy, 4);
        TextView detail = txt(on ? "Filtering ad and tracker domains across your apps."
                : starting ? "Connecting your local DNS filter." : "Block ad and tracker domains across your apps.", 14, 450, 0xffdeeadb);
        int displayWidth = getResources().getConfiguration().screenWidthDp;
        detail.setMaxWidth(dp(Math.max(135, Math.min(270, Math.round((displayWidth - 40) * .54f)))));
        copy.addView(detail, WRAP, WRAP);
        field.addView(copy, new FrameLayout.LayoutParams(MATCH, WRAP));
        hero.addView(field, MATCH, WRAP);
        LinearLayout actionWrap = col(); pad(actionWrap, 15, 0, 15, 15);
        LinearLayout action = action(on ? "Pause protection" : starting ? "Connecting…" : "Turn on protection",
                on ? "pause" : "power", LIME, FOREST, this::toggle);
        action.setEnabled(!starting); actionWrap.addView(action, MATCH, WRAP);
        hero.addView(actionWrap); body.addView(hero, MATCH, WRAP);
        if (!shownError.isEmpty()) { gap(body, 9); body.addView(txt(shownError, 14, 550, 0xff934923)); }
        gap(body, 22);
        section("Today", LocalDate.now().format(DateTimeFormatter.ofPattern("EEE, MMM d", Locale.getDefault())));
        stats();
        gap(body, 21); section("Filter list", null);
        body.addView(item("layers", "HaGeZi Light", filterCount(), () -> open(Page.RULES)), MATCH, WRAP);
        gap(body, 21); section("Latest block", null);
        List<ActivityLog.Entry> history = app.activity.recent();
        recentHead = history.isEmpty() ? null : history.get(0);
        latestName = txt(recentHead == null ? "No recent blocks" : recentHead.name, 16, 660, INK);
        latestTime = txt(recentHead == null ? "Blocked domains will appear here." : time(recentHead.time), 13, 450, MUTED);
        body.addView(item("clock", latestName, latestTime, () -> open(Page.ACTIVITY)));
    }

    private String filterCount() {
        return shownFilter == null ? "Loading filter list…" : number(shownFilter.domains.size()) + " domains";
    }

    private void stats() {
        LinearLayout cards = row(); cards.setBaselineAligned(false);
        blockedCount = stat(cards, "shield-off", "Blocked", TINT, GREEN);
        hgap(cards, 10);
        requestCount = stat(cards, "activity", "DNS requests", 0xffe7ebdf, 0xff51664e);
        body.addView(cards, MATCH, WRAP); setCounts();
    }

    private TextView stat(LinearLayout cards, String glyph, String label, int bg, int fg) {
        LinearLayout card = col(); pad(card, 14, 13, 12, 12); card.setBackground(shape(SURFACE, 19, LINE));
        Icon i = icon(glyph, fg, 18); i.setBackground(shape(bg, 10, 0)); card.addView(i, dp(31), dp(31));
        gap(card, 7); TextView count = txt("0", 29, 690, INK); count.setLetterSpacing(-.045f);
        card.addView(count); card.addView(txt(label, 13, 550, MUTED));
        cards.addView(card, new LinearLayout.LayoutParams(0, WRAP, 1)); return count;
    }

    private void setCounts() {
        if (blockedCount == null) return;
        long[] totals = app.activity.totals();
        blockedCount.setText(number(totals[0])); requestCount.setText(number(totals[1]));
    }

    private void rules() {
        title("Rules");
        LinearLayout list = card();
        LinearLayout top = row(); top.addView(iconBox("layers")); hgap(top, 12);
        LinearLayout names = col(); names.addView(txt("HaGeZi Light", 19, 700, INK));
        names.addView(txt(filterCount(), 14, 500, MUTED)); top.addView(names, weight());
        list.addView(top); gap(list, 15);
        list.addView(txt(shownFilter == null ? "Loading the built-in list…" : "List version  " + shownFilter.version, 13, 500, MUTED));
        gap(list, 12);
        LinearLayout refresh = action(shownUpdating ? "Checking for updates…" : "Check for updates", "refresh",
                TINT, FOREST, () -> app.rules.update());
        refresh.setEnabled(!shownUpdating);
        list.addView(refresh, MATCH, WRAP);
        if (!shownUpdate.isEmpty()) { gap(list, 9); list.addView(txt(shownUpdate, 13, 550, MUTED)); }
        body.addView(list, MATCH, WRAP); gap(body, 23); section("Personal rules", null);
        body.addView(item("check", "Allowed domains", ruleCount(true), () -> open(Page.ALLOW)));
        gap(body, 8); body.addView(item("ban", "Blocked domains", ruleCount(false), () -> open(Page.BLOCK)));
        gap(body, 23); section("Domain lookup", null);
        body.addView(item("search", "Check a domain", "See how your current rules handle it.", () -> domainDialog(null, null)));
    }

    private String ruleCount(boolean allowed) {
        int size = app.rules.list(allowed).size();
        return size == 1 ? "1 personal rule" : number(size) + " personal rules";
    }

    private void personal(boolean allowed) {
        TextView back = txt("‹   Rules", 15, 660, GREEN); back.setGravity(Gravity.CENTER_VERTICAL);
        tap(back, PAPER, 8, 0, () -> open(Page.RULES)); body.addView(back, dp(100), dp(45));
        title(allowed ? "Allowed domains" : "Blocked domains");
        body.addView(txt(allowed ? "Allow a domain and its subdomains. These rules take priority over blocked domains."
                : "Block a domain and its subdomains. Allowed domains take priority.", 15, 450, MUTED));
        gap(body, 16);
        body.addView(action("Add domain", "plus", FOREST, 0xfff1f7e7, () -> domainDialog(allowed, null)), MATCH, WRAP);
        gap(body, 22);
        List<String> domains = app.rules.list(allowed);
        if (domains.isEmpty()) {
            empty(allowed ? "check" : "ban", allowed ? "No allowed domains" : "No blocked domains",
                    "Your personal rules will appear here.");
        } else for (String name : domains) {
            LinearLayout line = row(); pad(line, 15, 6, 6, 6); line.setBackground(shape(SURFACE, 15, LINE));
            line.addView(txt(name, 16, 600, INK), weight());
            Icon delete = icon("delete", MUTED, 19); delete.setContentDescription("Remove " + name);
            delete.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
            tap(delete, SURFACE, 22, 0, () -> {
                app.rules.change(name, allowed, true); render(true);
            });
            line.addView(delete, dp(48), dp(48)); body.addView(line, MATCH, WRAP); gap(body, 6);
        }
    }

    private void activity() {
        title("Activity"); section("Today", null); stats(); gap(body, 23);
        List<ActivityLog.Entry> entries = app.activity.recent();
        recentHead = entries.isEmpty() ? null : entries.get(0);
        section("Recent blocks", null);
        if (entries.isEmpty()) {
            empty("activity", "No recent blocks", "When protection blocks a domain, you’ll see it here.");
        } else {
            body.addView(txt("The last 30 blocked domains are kept in memory on this device.", 13, 450, MUTED)); gap(body, 12);
            for (ActivityLog.Entry e : entries) {
                body.addView(item("shield-off", e.name, time(e.time), () -> new AlertDialog.Builder(this)
                        .setTitle(e.name).setMessage("Allow this domain and its subdomains on future DNS requests?")
                        .setNegativeButton("Close", null).setPositiveButton("Allow domain", (d, w) -> {
                            app.rules.change(e.name, true, false); toast("Domain allowed");
                        }).show()));
                gap(body, 6);
            }
            gap(body, 6); body.addView(action("Clear recent blocks", "delete", TINT, FOREST, () -> {
                app.activity.clearRecent(); render(true);
            }));
        }
    }

    private void empty(String glyph, String title, String detail) {
        LinearLayout panel = card(); panel.setGravity(Gravity.CENTER); panel.addView(iconBox(glyph));
        gap(panel, 9);
        TextView heading = txt(title, 17, 650, INK); heading.setGravity(Gravity.CENTER); panel.addView(heading);
        TextView copy = txt(detail, 14, 450, MUTED); copy.setGravity(Gravity.CENTER);
        gap(panel, 3); panel.addView(copy); body.addView(panel, MATCH, WRAP);
    }

    private void domainDialog(Boolean allowed, String initial) {
        LinearLayout box = col(); pad(box, 24, 8, 24, 0);
        EditText input = new EditText(this); input.setTextSize(16);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setSingleLine(); input.setHint("example.com");
        if (initial != null) input.setText(initial);
        box.addView(input);
        TextView result = txt("", 15, 600, GREEN);
        if (allowed == null) { gap(box, 6); box.addView(result); }
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(allowed == null ? "Check a domain" : allowed ? "Allow a domain" : "Block a domain")
                .setView(box).setNegativeButton("Close", null)
                .setPositiveButton(allowed == null ? "Check" : "Add", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                String name = Rules.normalize(input.getText().toString());
                if (allowed == null) {
                    result.setText(app.rules.filter == null ? "Filter list is loading."
                            : app.rules.blocked(name) ? "Blocked by your rules" : "Allowed by your rules");
                } else {
                    app.rules.change(name, allowed, false); dialog.dismiss(); render(true);
                }
            } catch (IllegalArgumentException e) { input.setError("Enter a domain such as example.com"); }
        }));
        dialog.show();
    }

    private void toggle() {
        if (app.state == QuietApp.State.ON) {
            startService(new Intent(this, ProtectionService.class).setAction(ProtectionService.STOP));
            return;
        }
        if (app.state == QuietApp.State.STARTING) return;
        try {
            Intent consent = VpnService.prepare(this);
            if (consent != null) startActivityForResult(consent, VPN_REQUEST);
            else startProtection();
        } catch (RuntimeException e) { toast("Android could not open VPN setup."); }
    }

    @Override protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (code == VPN_REQUEST) {
            if (result == RESULT_OK) startProtection();
            else toast("VPN permission is needed to turn on protection.");
        }
    }

    private void startProtection() {
        startForegroundService(new Intent(this, ProtectionService.class).setAction(ProtectionService.START));
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED && !getPreferences(0).getBoolean("askedNotifications", false)) {
            getPreferences(0).edit().putBoolean("askedNotifications", true).apply();
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_REQUEST);
        }
    }

    private void about() {
        new AlertDialog.Builder(this).setTitle("About QuietDNS")
                .setMessage("QuietDNS filters DNS lookups on your phone. Allowed requests use your current network’s DNS resolver. The app never reads web page contents.\n\n"
                        + "Android allows one VPN at a time. Apps that use their own encrypted DNS can bypass filtering. Ads that share a domain with the content, including many YouTube ads, can still appear.\n\n"
                        + "If the counters stay at zero, check for a custom Private DNS provider in Android or Secure DNS in your browser. If a site stops working, tap a recent block to allow that domain.\n\n"
                        + "Version 1.0  ·  HaGeZi Light filter")
                .setPositiveButton("Done", null).setNeutralButton("Licenses", (d, w) -> licenses()).show();
    }

    private void licenses() {
        String[] titles = {"QuietDNS and HaGeZi Light · GPL v3", "DM Sans · SIL Open Font License", "DNS engine · Open source notices"};
        String[] files = {"FILTER-LICENSE.txt", "FONT-LICENSE.txt", "ENGINE-NOTICES.txt"};
        new AlertDialog.Builder(this).setTitle("Open source licenses").setItems(titles, (d, which) -> {
            try (InputStream in = getAssets().open(files[which]); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[4096]; int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                new AlertDialog.Builder(this).setTitle(titles[which])
                        .setMessage(new String(out.toByteArray(), StandardCharsets.UTF_8)).setPositiveButton("Done", null).show();
            } catch (IOException e) { toast("Couldn’t open the license."); }
        }).setNegativeButton("Close", null).show();
    }

    private void section(String name, String note) {
        LinearLayout bar = row();
        TextView head = txt(name, 20, 680, INK); head.setLetterSpacing(-.035f);
        bar.addView(head, weight());
        if (note != null) bar.addView(txt(note, 12, 550, MUTED));
        body.addView(bar, MATCH, WRAP); gap(body, 9);
    }

    private void title(String value) {
        TextView title = txt(value, 33, 700, INK); title.setLetterSpacing(-.05f);
        body.addView(title); gap(body, 16);
    }

    private LinearLayout item(String glyph, String name, String detail, Runnable click) {
        return item(glyph, txt(name, 16, 660, INK), txt(detail, 13, 450, MUTED), click);
    }
    private LinearLayout item(String glyph, TextView name, TextView detail, Runnable click) {
        LinearLayout row = row(); pad(row, 14, 13, 12, 13); row.setMinimumHeight(dp(70));
        row.addView(iconBox(glyph)); hgap(row, 12);
        LinearLayout labels = col(); labels.addView(name);
        labels.addView(detail); row.addView(labels, weight()); hgap(row, 4);
        row.addView(icon("right", MUTED, 17), dp(20), dp(23)); tap(row, SURFACE, 17, LINE, click);
        return row;
    }

    private Icon iconBox(String glyph) {
        Icon i = icon(glyph, GREEN, 21);
        i.setBackground(shape(TINT, 13, 0));
        i.setLayoutParams(new LinearLayout.LayoutParams(dp(39), dp(39)));
        return i;
    }

    private LinearLayout action(String label, String glyph, int bg, int fg, Runnable click) {
        LinearLayout a = row(); a.setGravity(Gravity.CENTER); a.setMinimumHeight(dp(48)); pad(a, 10, 8, 10, 8);
        a.addView(icon(glyph, fg, 18), dp(20), dp(22)); hgap(a, 8); a.addView(txt(label, 15, 700, fg));
        tap(a, bg, 13, 0, click); return a;
    }

    private LinearLayout card() {
        LinearLayout c = col(); c.setBackground(shape(SURFACE, 19, LINE)); pad(c, 16, 17, 16, 17); return c;
    }
    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    private String number(long n) { return NumberFormat.getIntegerInstance().format(n); }
    private String time(long ms) {
        return Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT));
    }
    private void toast(String value) { Toast.makeText(this, value, Toast.LENGTH_SHORT).show(); }
    private LinearLayout col() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private LinearLayout row() { LinearLayout l = new LinearLayout(this); l.setGravity(Gravity.CENTER_VERTICAL); return l; }
    private LinearLayout.LayoutParams weight() { return new LinearLayout.LayoutParams(0, WRAP, 1); }
    private void pad(View v, int a, int b, int c, int d) { v.setPadding(dp(a), dp(b), dp(c), dp(d)); }
    private void gap(LinearLayout l, int size) { l.addView(new Space(this), 1, dp(size)); }
    private void hgap(LinearLayout l, int size) { l.addView(new Space(this), dp(size), 1); }
    private Icon icon(String name, int fg, int size) { return new Icon(this, name, fg, size); }
    private TextView txt(String value, int size, int weight, int color) {
        TextView t = new TextView(this); t.setText(value); t.setTextSize(size); t.setTextColor(color);
        t.setTypeface(Typeface.create(font, weight, false)); t.setIncludeFontPadding(false);
        t.setBreakStrategy(android.graphics.text.LineBreaker.BREAK_STRATEGY_SIMPLE); return t;
    }
    private GradientDrawable shape(int color, int radius, int border) {
        GradientDrawable s = new GradientDrawable(); s.setColor(color); s.setCornerRadius(dp(radius));
        if (border != 0) s.setStroke(dp(1), border); return s;
    }
    private void tap(View v, int bg, int radius, int border, Runnable click) {
        v.setBackground(new RippleDrawable(ColorStateList.valueOf(0x222c563e),
                shape(bg, radius, border), shape(Color.WHITE, radius, 0)));
        v.setFocusable(true); v.setOnClickListener(w -> click.run());
    }
}
