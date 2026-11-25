// SPDX-License-Identifier: GPL-3.0-or-later
package com.emanuelef.remote_capture;

import android.content.Context;
import android.content.Intent;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.PreferenceManager;
import com.emanuelef.remote_capture.model.CaptureSettings;
import com.emanuelef.remote_capture.model.Prefs;

public final class PacketLabConfig {
    private static final String DETAIL = "packet_lab_detail";
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
            .setMessage(null)
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
