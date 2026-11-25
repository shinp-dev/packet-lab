// SPDX-License-Identifier: GPL-3.0-or-later
package com.emanuelef.remote_capture;

import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AlertDialog;

/** Non-exported, user-initiated foreground activity for VPN consent. */
public class PacketCaptureActivity extends AppCompatActivity {
    private CaptureHelper helper;
    private boolean submitted;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null || !PacketOverlayService.isStartPending()
                || CaptureService.isServiceActive()) {
            finishAndRemoveTask();
            return;
        }
        TextView message = new TextView(this);
        message.setText("Packet Lab：通信記録の準備中");
        message.setPadding(24, 24, 24, 24);
        setContentView(message);
        helper = new CaptureHelper(this);
        helper.setListener(success -> {
            submitted = success;
            PacketOverlayService.startResult(success);
            if (!success)
                Toast.makeText(this, "記録を開始できませんでした", Toast.LENGTH_SHORT).show();
            finishAndRemoveTask();
        });
        if (Utils.getRunningVpn(this) != null) {
            new AlertDialog.Builder(this)
                .setTitle(R.string.active_vpn_detected)
                .setMessage(R.string.disconnect_vpn_confirm)
                .setPositiveButton(R.string.ok, (d, w) -> begin())
                .setNegativeButton(R.string.cancel_action, (d, w) -> finishAndRemoveTask())
                .setOnCancelListener(d -> finishAndRemoveTask())
                .show();
        } else {
            begin();
        }
    }

    private void begin() {
        if (!PacketOverlayService.isStartPending() || CaptureService.isServiceActive()) {
            finishAndRemoveTask();
            return;
        }
        PCAPdroid.getInstance().setLoadedPcapBasename(null);
        PCAPdroid.getInstance().setIsDecryptingPcap(false);
        VpnReconnectService.stopService();
        helper.startCapture(PacketLabConfig.localSettings(this));
    }

    @Override
    protected void onDestroy() {
        if (!submitted && isFinishing())
            PacketOverlayService.startResult(false);
        super.onDestroy();
    }
}
