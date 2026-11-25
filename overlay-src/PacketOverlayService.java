// SPDX-License-Identifier: GPL-3.0-or-later
package com.emanuelef.remote_capture;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.net.VpnService;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Toast;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;
import com.emanuelef.remote_capture.activities.MainActivity;

/** Visible, movable, user-controlled panel; packet capture itself is done by CaptureService. */
public class PacketOverlayService extends Service implements LifecycleOwner {
    private static final String CHANNEL = "packet_lab_overlay";
    private static final int NOTIFICATION_ID = 74021;
    private static long pendingUntil;
    private static boolean requested;
    private final LifecycleRegistry lifecycle = new LifecycleRegistry(this);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private WindowManager.LayoutParams params;
    private LinearLayout panel;
    private Button record;
    private Button close;
    private boolean started;
    private boolean stopping;
    private boolean closing;
    private boolean attached;
    private long stoppingSince;
    private String lastLabel = "";

    public static boolean isStartPending() {
        return pendingUntil > SystemClock.elapsedRealtime();
    }

    public static void startResult(boolean success) {
        requested = success;
        pendingUntil = success ? SystemClock.elapsedRealtime() + 20000 : 0;
    }

    public static void show(Activity activity) {
        if (Build.VERSION.SDK_INT < 26) {
            Toast.makeText(activity, "操作パネルはAndroid 8以上で利用できます", Toast.LENGTH_LONG).show();
            return;
        }
        if (!Settings.canDrawOverlays(activity)) {
            try {
                activity.startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + activity.getPackageName())));
                Toast.makeText(activity, "許可後、もう一度「操作パネルを表示」を選択してください", Toast.LENGTH_LONG).show();
            } catch (RuntimeException e) {
                Toast.makeText(activity, "重ねて表示する権限の設定を開けません", Toast.LENGTH_LONG).show();
            }
            return;
        }
        try {
            ContextCompat.startForegroundService(activity, new Intent(activity, PacketOverlayService.class));
        } catch (RuntimeException e) {
            Toast.makeText(activity, "操作パネルを開始できません", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public Lifecycle getLifecycle() { return lifecycle; }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_CREATE);
        if (Build.VERSION.SDK_INT < 26) {
            stopSelf();
            return;
        }
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL,
            "Packet Lab 操作パネル", NotificationManager.IMPORTANCE_LOW));
        Notification n = notification("操作パネル待機中");
        if (Build.VERSION.SDK_INT >= 34)
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else
            startForeground(NOTIFICATION_ID, n);
        if (!Settings.canDrawOverlays(this)) {
            stopSelf();
            return;
        }
        wm = getSystemService(WindowManager.class);
        try {
            createPanel();
        } catch (RuntimeException e) {
            Toast.makeText(this, "操作パネルを表示できません", Toast.LENGTH_SHORT).show();
            stopSelf();
            return;
        }
        CaptureService.observeStatus(this, status -> {
            started = status == CaptureService.ServiceStatus.STARTED;
            if (started || requested) startResult(false);
            render();
        });
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START);
        handler.post(tick);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_NOT_STICKY;
    }

    private Intent resultsIntent() {
        return new Intent(this, MainActivity.class)
            .putExtra("packet_lab_results", true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    }

    private Notification notification(String state) {
        PendingIntent open = PendingIntent.getActivity(this, 74021, resultsIntent(),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.packet_lab_icon)
            .setContentTitle("Packet Lab")
            .setContentText(state)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build();
    }

    private int dp(float n) {
        return Math.round(n * getResources().getDisplayMetrics().density);
    }

    private Button button(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(12);
        b.setTextColor(Color.WHITE);
        b.setAllCaps(false);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(dp(7), 0, dp(7), 0);
        return b;
    }

    private void color(Button button, int value) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(value);
        bg.setCornerRadius(dp(7));
        button.setBackground(bg);
    }

    private void add(Button b, int width) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(width), dp(44));
        lp.setMargins(dp(2), 0, dp(2), 0);
        panel.addView(b, lp);
    }

    private void createPanel() {
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.HORIZONTAL);
        panel.setPadding(dp(3), dp(3), dp(3), dp(3));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xE6222B35);
        bg.setCornerRadius(dp(10));
        panel.setBackground(bg);
        Button drag = button("≡");
        drag.setContentDescription("操作パネルを移動");
        color(drag, 0xFF43505D);
        record = button("詳細開始");
        Button results = button("結果");
        color(results, 0xFF43505D);
        close = button("×");
        close.setContentDescription("記録を停止して操作パネルを閉じる");
        color(close, 0xFF43505D);
        add(drag, 36);
        add(record, 94);
        add(results, 54);
        add(close, 36);
        params = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.LEFT;
        params.x = getResources().getDisplayMetrics().widthPixels - dp(246);
        params.y = dp(110);
        clamp();
        wm.addView(panel, params);
        attached = true;

        record.setOnClickListener(v -> {
            if (stopping || isStartPending()) return;
            if (CaptureService.isServiceActive()) {
                stopCapture(false);
            } else {
                pendingUntil = SystemClock.elapsedRealtime() + 120000;
                requested = false;
                try {
                    if (VpnService.prepare(this) == null && Utils.getRunningVpn(this) == null
                            && Utils.hasLocalNetworkPermission(this)) {
                        PCAPdroid.getInstance().setLoadedPcapBasename(null);
                        PCAPdroid.getInstance().setIsDecryptingPcap(false);
                        VpnReconnectService.stopService();
                        CaptureHelper helper = new CaptureHelper(this);
                        helper.setListener(success -> {
                            startResult(success);
                            if (!success)
                                Toast.makeText(this, "本体アプリから開始してください", Toast.LENGTH_SHORT).show();
                            render();
                        });
                        helper.startCapture(PacketLabConfig.localSettings(this));
                    } else {
                        startActivity(new Intent(this, PacketCaptureActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    }
                } catch (RuntimeException e) {
                    startResult(false);
                    Toast.makeText(this, "本体アプリから開始してください", Toast.LENGTH_SHORT).show();
                }
                render();
            }
        });
        results.setOnClickListener(v -> {
            try { startActivity(resultsIntent()); }
            catch (RuntimeException e) {
                Toast.makeText(this, "本体アプリを開いてください", Toast.LENGTH_SHORT).show();
            }
        });
        close.setOnClickListener(v -> stopCapture(true));
        drag.setOnTouchListener(new View.OnTouchListener() {
            float startX, startY;
            int x, y;
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    startX = event.getRawX();
                    startY = event.getRawY();
                    x = params.x;
                    y = params.y;
                    return true;
                } else if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                    params.x = x + Math.round(event.getRawX() - startX);
                    params.y = y + Math.round(event.getRawY() - startY);
                    move();
                    return true;
                }
                return true;
            }
        });
    }

    private void clamp() {
        int width = panel.getWidth() > 0 ? panel.getWidth() : dp(246);
        int height = panel.getHeight() > 0 ? panel.getHeight() : dp(54);
        params.x = Math.max(0, Math.min(params.x, getResources().getDisplayMetrics().widthPixels - width));
        params.y = Math.max(0, Math.min(params.y, getResources().getDisplayMetrics().heightPixels - height));
    }

    private void move() {
        if (!attached) return;
        clamp();
        try { wm.updateViewLayout(panel, params); }
        catch (RuntimeException e) { stopSelf(); }
    }

    @Override
    public void onConfigurationChanged(Configuration config) {
        super.onConfigurationChanged(config);
        move();
    }

    private void stopCapture(boolean closePanel) {
        if (isStartPending()) return;
        if (CaptureService.isServiceActive()) {
            if (CaptureService.isAlwaysOnVPN()) {
                Toast.makeText(this, "AndroidのVPN設定から停止してください", Toast.LENGTH_LONG).show();
                return;
            }
            closing = closePanel;
            stopping = true;
            stoppingSince = SystemClock.elapsedRealtime();
            CaptureService.stopService();
            render();
        } else if (closePanel) stopSelf();
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!Settings.canDrawOverlays(PacketOverlayService.this)) {
                if (CaptureService.isServiceActive()) CaptureService.stopService();
                stopSelf();
                return;
            }
            render();
            if (!closing || CaptureService.isServiceActive())
                handler.postDelayed(this, 500);
        }
    };

    private void render() {
        if (!attached) return;
        boolean active = CaptureService.isServiceActive();
        if (!isStartPending() && pendingUntil != 0) {
            startResult(false);
        }
        if (stopping && !active) {
            stopping = false;
            if (closing) {
                stopSelf();
                return;
            }
        } else if (stopping && SystemClock.elapsedRealtime() - stoppingSince > 15000) {
            // Stop is asynchronous; allow retry if the engine failed to terminate.
            stopping = false;
            closing = false;
        }
        String label;
        int value;
        if (stopping) {
            label = "停止中";
            value = 0xFF626B75;
        } else if (active && started) {
            label = "停止";
            value = 0xFFB83939;
        } else if (active || isStartPending()) {
            // Permit stop once the engine thread exists even before native STARTED.
            label = active ? "停止" : "準備中";
            value = active ? 0xFFB83939 : 0xFF626B75;
        } else {
            label = PacketLabConfig.detail(this) ? "詳細開始" : "軽量開始";
            value = 0xFF177B46;
        }
        record.setEnabled(!stopping && !isStartPending());
        close.setEnabled(!stopping && !isStartPending());
        if (!label.equals(lastLabel)) {
            lastLabel = label;
            record.setText(label);
            color(record, value);
            getSystemService(NotificationManager.class).notify(NOTIFICATION_ID,
                notification(active ? "通信を記録中" : isStartPending() ? "記録準備中" : "操作パネル待機中"));
        }
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        startResult(false);
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY);
        if (attached) {
            try { wm.removeView(panel); } catch (RuntimeException ignored) {}
            attached = false;
        }
        stopForeground(true);
        super.onDestroy();
    }
}
