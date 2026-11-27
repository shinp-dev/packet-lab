// SPDX-License-Identifier: GPL-3.0-or-later
package com.emanuelef.remote_capture;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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

    public static void chooseWatch(AppCompatActivity activity) {
        PackageManager pm = activity.getPackageManager();
        List<ApplicationInfo> apps = new ArrayList<>();
        for (ApplicationInfo app : pm.getInstalledApplications(0)) {
            if (!activity.getPackageName().equals(app.packageName)
                    && uniqueUid(pm, app.packageName, app.uid)) apps.add(app);
        }
        apps.sort(Comparator.comparing((ApplicationInfo app) -> pm.getApplicationLabel(app).toString(),
                String.CASE_INSENSITIVE_ORDER).thenComparing(app -> app.packageName));
        String[] labels = new String[apps.size() + 1];
        labels[0] = "通知しない";
        int checked = 0;
        String previous = watchedPackage(activity);
        for (int i = 0; i < apps.size(); i++) {
            ApplicationInfo app = apps.get(i);
            labels[i + 1] = pm.getApplicationLabel(app) + "\n" + app.packageName;
            if (app.packageName.equals(previous)) checked = i + 1;
        }
        new AlertDialog.Builder(activity)
            .setTitle("通信通知するアプリ")
            .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                String pkg = which == 0 ? "" : apps.get(which - 1).packageName;
                PreferenceManager.getDefaultSharedPreferences(activity).edit().putString(WATCH, pkg).apply();
                dialog.dismiss();
                if (!pkg.isEmpty()) PacketOverlayService.show(activity);
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
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
