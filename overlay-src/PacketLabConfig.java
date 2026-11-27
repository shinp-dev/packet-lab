// SPDX-License-Identifier: GPL-3.0-or-later
package com.emanuelef.remote_capture;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import com.emanuelef.remote_capture.model.ConnectionDescriptor;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.PreferenceManager;
import com.emanuelef.remote_capture.model.CaptureSettings;
import com.emanuelef.remote_capture.model.Prefs;

public final class PacketLabConfig {
    private static final String DETAIL = "packet_lab_detail";
    private static final String WATCH = "packet_lab_watch_package";
    private PacketLabConfig() {}

    public static boolean detail(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context).getBoolean(DETAIL, true);
    }

    public static void chooseMode(AppCompatActivity activity) {
        new AlertDialog.Builder(activity)
            .setTitle("記録モード設定")
            .setSingleChoiceItems(new String[]{
                "軽量：接続先・通信量を中心に記録",
                "詳細：平文HTTPやペイロードも記録"
            }, detail(activity) ? 1 : 0, (dialog, which) -> {
                PreferenceManager.getDefaultSharedPreferences(activity)
                    .edit().putBoolean(DETAIL, which == 1).apply();
                dialog.dismiss();
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    public static String watchedPackage(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context).getString(WATCH, "");
    }

    // A shared UID cannot be attributed to a single package reliably.
    public static boolean uniqueUid(PackageManager pm, String pkg, int uid) {
        if (uid < 0 || pkg == null || pkg.isEmpty()) return false;
        String[] owners = pm.getPackagesForUid(uid);
        return owners != null && owners.length == 1 && pkg.equals(owners[0]);
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static final class WatchApp {
        final ApplicationInfo info;
        final String label;
        final String searchable;
        final boolean system;
        final long lastSeen;

        WatchApp(ApplicationInfo info, String label, long lastSeen) {
            this.info = info;
            this.label = label;
            this.searchable = (label + " " + info.packageName).toLowerCase(Locale.ROOT);
            this.system = (info.flags & (ApplicationInfo.FLAG_SYSTEM
                    | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
            this.lastSeen = lastSeen;
        }
    }

    // Use timestamps from the current/latest retained live capture, not OS usage access.
    private static Map<Integer, Long> recentTraffic() {
        Map<Integer, Long> latest = new HashMap<>();
        ConnectionsRegister register = CaptureService.getConnsRegister();
        if (register == null || CaptureService.isReadingFromPcapFile()) return latest;
        synchronized (register) {
            int count = register.getConnCount();
            for (int i = 0; i < count; i++) {
                ConnectionDescriptor conn = register.getConn(i);
                if (conn == null || conn.uid < 0 || (conn.sent_bytes <= 0 && conn.rcvd_bytes <= 0))
                    continue;
                Long old = latest.get(conn.uid);
                if (old == null || conn.last_seen > old) latest.put(conn.uid, conn.last_seen);
            }
        }
        return latest;
    }

    public static void chooseWatch(AppCompatActivity activity) {
        PackageManager pm = activity.getPackageManager();
        String previous = watchedPackage(activity);
        Map<Integer, Long> latest = recentTraffic();
        List<WatchApp> all = new ArrayList<>();
        for (ApplicationInfo app : pm.getInstalledApplications(0)) {
            if (activity.getPackageName().equals(app.packageName)
                    || !uniqueUid(pm, app.packageName, app.uid)) continue;
            Long seen = latest.get(app.uid);
            CharSequence label = pm.getApplicationLabel(app);
            all.add(new WatchApp(app, label == null ? app.packageName : label.toString(),
                    seen == null ? -1 : seen));
        }
        all.sort(Comparator.comparing((WatchApp a) -> !previous.equals(a.info.packageName))
                .thenComparing(Comparator.comparingLong((WatchApp a) -> a.lastSeen).reversed())
                .thenComparing(a -> a.label, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(a -> a.info.packageName));

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(activity, 16), dp(activity, 4), dp(activity, 16), 0);

        EditText search = new EditText(activity);
        search.setSingleLine(true);
        search.setHint("アプリ名・パッケージ名で検索");
        root.addView(search, new LinearLayout.LayoutParams(-1, -2));

        CheckBox showSystem = new CheckBox(activity);
        showSystem.setText("システムアプリも表示");
        showSystem.setChecked(false);
        root.addView(showSystem);

        TextView count = new TextView(activity);
        count.setTextSize(12);
        count.setPadding(0, dp(activity, 4), 0, dp(activity, 4));
        root.addView(count);

        ListView list = new ListView(activity);
        list.setDividerHeight(dp(activity, 1));
        int height = Math.min(dp(activity, 400),
                activity.getResources().getDisplayMetrics().heightPixels * 45 / 100);
        root.addView(list, new LinearLayout.LayoutParams(-1, Math.max(dp(activity, 100), height)));

        List<WatchApp> visible = new ArrayList<>();
        BaseAdapter adapter = new BaseAdapter() {
            @Override public int getCount() { return visible.size(); }
            @Override public WatchApp getItem(int position) { return visible.get(position); }
            @Override public long getItemId(int position) { return position; }
            @Override public View getView(int position, View convertView, ViewGroup parent) {
                LinearLayout row;
                ImageView icon;
                TextView title, subtitle;
                if (convertView == null) {
                    row = new LinearLayout(activity);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    row.setPadding(dp(activity, 4), dp(activity, 8), dp(activity, 4), dp(activity, 8));
                    icon = new ImageView(activity);
                    icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
                    LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(
                            dp(activity, 40), dp(activity, 40));
                    iconParams.setMarginEnd(dp(activity, 12));
                    row.addView(icon, iconParams);
                    LinearLayout labels = new LinearLayout(activity);
                    labels.setOrientation(LinearLayout.VERTICAL);
                    title = new TextView(activity);
                    title.setTextSize(16);
                    subtitle = new TextView(activity);
                    subtitle.setTextSize(12);
                    labels.addView(title);
                    labels.addView(subtitle);
                    row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
                } else {
                    row = (LinearLayout) convertView;
                    icon = (ImageView) row.getChildAt(0);
                    LinearLayout labels = (LinearLayout) row.getChildAt(1);
                    title = (TextView) labels.getChildAt(0);
                    subtitle = (TextView) labels.getChildAt(1);
                }
                WatchApp item = getItem(position);
                boolean selected = previous.equals(item.info.packageName);
                title.setText(item.label + (selected ? "  ［選択中］" : ""));
                title.setTypeface(null, selected ? Typeface.BOLD : Typeface.NORMAL);
                subtitle.setText(item.info.packageName + (item.lastSeen >= 0 ? "  ・通信実績あり" : "")
                        + (item.system ? "  ・システム" : ""));
                try { icon.setImageDrawable(pm.getApplicationIcon(item.info)); }
                catch (RuntimeException e) { icon.setImageDrawable(pm.getDefaultActivityIcon()); }
                return row;
            }
        };
        list.setAdapter(adapter);
        Runnable filter = () -> {
            String query = search.getText().toString().trim().toLowerCase(Locale.ROOT);
            visible.clear();
            for (WatchApp item : all) {
                boolean selected = previous.equals(item.info.packageName);
                if (item.system && !showSystem.isChecked() && !selected) continue;
                if (!query.isEmpty() && !item.searchable.contains(query)) continue;
                visible.add(item);
            }
            count.setText(visible.isEmpty() ? "該当するアプリがありません"
                    : visible.size() + "件 ・ 選択中／最近の通信順");
            adapter.notifyDataSetChanged();
            list.setSelection(0);
        };
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { filter.run(); }
            @Override public void afterTextChanged(Editable s) {}
        });
        showSystem.setOnCheckedChangeListener((button, checked) -> filter.run());
        filter.run();

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("通信通知するアプリ")
                .setView(root)
                .setNeutralButton("通知しない", (d, which) ->
                        PreferenceManager.getDefaultSharedPreferences(activity).edit().putString(WATCH, "").apply())
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        list.setOnItemClickListener((parent, view, position, id) -> {
            WatchApp item = visible.get(position);
            // Revalidate after the dialog has been open, e.g. after package changes.
            try {
                ApplicationInfo current = pm.getApplicationInfo(item.info.packageName, 0);
                if (!uniqueUid(pm, current.packageName, current.uid)) {
                    android.widget.Toast.makeText(activity, "アプリを識別できません。選び直してください。",
                            android.widget.Toast.LENGTH_SHORT).show();
                    return;
                }
                PreferenceManager.getDefaultSharedPreferences(activity).edit()
                        .putString(WATCH, current.packageName).apply();
            } catch (PackageManager.NameNotFoundException | RuntimeException e) {
                android.widget.Toast.makeText(activity, "アプリを確認できません。選び直してください。",
                        android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
            dialog.dismiss();
            PacketOverlayService.show(activity);
        });
        dialog.show();
        // Keep the list visible at first; open the keyboard only on a tap.
        if (dialog.getWindow() != null) dialog.getWindow().setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
                        | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    }

    public static CaptureSettings localSettings(Context context) {
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit().putString(Prefs.PREF_CONNECTIONS_LOG_SIZE, "1024").apply();
        Intent opts = new Intent()
            .putExtra("pcap_dump_mode", "none")
            .putExtra(Prefs.PREF_IP_MODE, Prefs.IP_MODE_BOTH)
            .putExtra(Prefs.PREF_FULL_PAYLOAD, detail(context))
            .putExtra(Prefs.PREF_ROOT_CAPTURE, false)
            .putExtra(Prefs.PREF_TLS_DECRYPTION_KEY, false)
            .putExtra(Prefs.PREF_SOCKS5_ENABLED_KEY, false)
            .putExtra(Prefs.PREF_AUTO_BLOCK_PRIVATE_DNS, false);
        return new CaptureSettings(context, opts);
    }
}
