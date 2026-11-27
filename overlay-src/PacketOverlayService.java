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
import android.widget.TextView;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import com.emanuelef.remote_capture.model.AppStats;
import com.emanuelef.remote_capture.model.ConnectionDescriptor;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;
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
    private LinearLayout controls;
    private LinearLayout traffic;
    private TextView trafficTitle;
    private LinearLayout trafficRows;
    private ConnectionsRegister watchRegister;
    private String watchPackage = "";
    private int watchUid = -1;
    private static final long TRAFFIC_VISIBLE_MS = 1500;
    private static final int MAX_TRAFFIC_ROWS = 5;
    private static final Pattern HOST_NAME = Pattern.compile(
            "(?i)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z](?:[a-z0-9-]{0,61}[a-z0-9])?\\.?");
    private static final long TRAFFIC_STAGGER_MS = 65;
    private final Object trafficToken = new Object();
    private long watchTx, watchRx;
    private Map<Integer, long[]> watchConnections = new HashMap<>();
    private static final class TrafficEvent {
        final long time;
        final int id;
        final String endpoint;
        final String domain;
        TrafficEvent(long time, int id, String endpoint, String domain) {
            this.time = time;
            this.id = id;
            this.endpoint = endpoint;
            this.domain = domain;
        }
    }
    private String watchLabel = "";
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
            if (!started && !CaptureService.isServiceActive()) resetWatch();
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
        controls.addView(b, lp);
    }

    private void createPanel() {
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        panel.addView(controls);
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
        traffic = new LinearLayout(this);
        traffic.setOrientation(LinearLayout.VERTICAL);
        traffic.setVisibility(View.GONE);
        trafficTitle = new TextView(this);
        trafficTitle.setTextColor(Color.WHITE);
        trafficTitle.setTextSize(11);
        trafficTitle.setMaxLines(1);
        trafficTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        trafficTitle.setPadding(dp(5), dp(2), dp(5), 0);
        traffic.addView(trafficTitle);
        trafficRows = new LinearLayout(this);
        trafficRows.setOrientation(LinearLayout.VERTICAL);
        traffic.addView(trafficRows);
        panel.addView(traffic, new LinearLayout.LayoutParams(dp(236), LinearLayout.LayoutParams.WRAP_CONTENT));
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
            hideTraffic();
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
            updateTraffic();
            if (!closing || CaptureService.isServiceActive())
                handler.postDelayed(this, 500);
        }
    };

    private void hideTraffic() {
        handler.removeCallbacksAndMessages(trafficToken);
        if (trafficRows != null) trafficRows.removeAllViews();
        if (traffic != null && traffic.getVisibility() != View.GONE) {
            traffic.setVisibility(View.GONE);
            panel.post(() -> { if (attached) move(); });
        }
    }

    private void showTrafficEvent(TrafficEvent event) {
        if (!attached || stopping || !CaptureService.isServiceActive()
                || !watchPackage.equals(PacketLabConfig.watchedPackage(this))
                || watchRegister != CaptureService.getConnsRegister()) return;
        while (trafficRows.getChildCount() >= MAX_TRAFFIC_ROWS) {
            // Expiration callbacks for removed rows are harmless and still bounded in time.
            trafficRows.removeViewAt(trafficRows.getChildCount() - 1);
        }
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(5), dp(2), dp(5), dp(2));
        TextView endpoint = new TextView(this);
        endpoint.setTextColor(Color.WHITE);
        endpoint.setTextSize(10);
        endpoint.setMaxLines(2);
        endpoint.setEllipsize(android.text.TextUtils.TruncateAt.END);
        endpoint.setIncludeFontPadding(false);
        endpoint.setText(event.endpoint);
        row.addView(endpoint, new LinearLayout.LayoutParams(-1, -2));
        if (!event.domain.isEmpty()) {
            TextView domain = new TextView(this);
            domain.setTextColor(0xFFD2F0DF);
            domain.setTextSize(10);
            domain.setSingleLine(true);
            domain.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            domain.setIncludeFontPadding(false);
            domain.setText(event.domain);
            row.addView(domain, new LinearLayout.LayoutParams(-1, -2));
        }
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(3));
        bg.setColor(0xFF177B46);
        row.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(2);
        trafficRows.addView(row, 0, lp);
        trafficTitle.setText(watchLabel + "：通信あり");
        traffic.setVisibility(View.VISIBLE);
        panel.post(() -> { if (attached) move(); });
        handler.postAtTime(() -> {
            if (row.getParent() != trafficRows) return;
            trafficRows.removeView(row);
            if (trafficRows.getChildCount() == 0) traffic.setVisibility(View.GONE);
            if (attached) panel.post(() -> { if (attached) move(); });
        }, trafficToken, SystemClock.uptimeMillis() + TRAFFIC_VISIBLE_MS);
    }

    private void resetWatch() {
        watchRegister = null;
        watchPackage = "";
        watchUid = -1;
        watchTx = watchRx = 0;
        watchConnections.clear();
        hideTraffic();
    }

    private void updateTraffic() {
        if (!attached || stopping || !CaptureService.isServiceActive()
                || CaptureService.isReadingFromPcapFile()) {
            resetWatch();
            return;
        }
        String pkg = PacketLabConfig.watchedPackage(this);
        ConnectionsRegister reg = CaptureService.getConnsRegister();
        if (pkg.isEmpty() || reg == null) { resetWatch(); return; }
        PackageManager pm = getPackageManager();
        ApplicationInfo app;
        try {
            app = pm.getApplicationInfo(pkg, 0);
            if (!PacketLabConfig.uniqueUid(pm, pkg, app.uid)) { resetWatch(); return; }
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            resetWatch();
            return;
        }
        long tx, rx;
        Map<Integer, long[]> next = new HashMap<>();
        List<TrafficEvent> events = new ArrayList<>();
        boolean sameTarget = watchRegister == reg && watchPackage.equals(pkg) && watchUid == app.uid;
        // Copy counters and collect actually changed connections under the register lock.
        synchronized (reg) {
            AppStats stats = reg.getAppStats(app.uid);
            tx = stats == null ? 0 : stats.sentBytes;
            rx = stats == null ? 0 : stats.rcvdBytes;
            int count = reg.getConnCount();
            for (int i = 0; i < count; i++) {
                ConnectionDescriptor conn = reg.getConn(i);
                if (conn == null || conn.uid != app.uid) continue;
                long[] old = sameTarget ? watchConnections.get(conn.incr_id) : null;
                next.put(conn.incr_id, new long[]{conn.first_seen, conn.sent_bytes, conn.rcvd_bytes});
                boolean known = old != null && old[0] == conn.first_seen;
                boolean changed = known ? conn.sent_bytes > old[1] || conn.rcvd_bytes > old[2]
                        : conn.sent_bytes > 0 || conn.rcvd_bytes > 0;
                if (sameTarget && changed) {
                    events.add(new TrafficEvent(conn.last_seen, conn.incr_id,
                            protocolLabel(conn) + "  " +
                            (conn.dst_ip == null || conn.dst_ip.isEmpty() ? "IP不明" : conn.dst_ip),
                            domainLabel(conn)));
                }
            }
        }
        watchConnections = next;
        if (!sameTarget || tx < watchTx || rx < watchRx) {
            // Baseline a newly selected app/session; never announce historical traffic.
            watchRegister = reg;
            watchPackage = pkg;
            watchUid = app.uid;
            watchLabel = pm.getApplicationLabel(app).toString();
            watchTx = tx;
            watchRx = rx;
            hideTraffic();
            return;
        }
        boolean changed = tx > watchTx || rx > watchRx;
        watchTx = tx;
        watchRx = rx;
        if (!changed) return;

        // A connection may have already left the bounded register. Do not guess its endpoint.
        if (events.isEmpty()) events.add(new TrafficEvent(0, 0, "プロトコル・接続先IP不明", ""));
        events.sort(Comparator.comparingLong((TrafficEvent e) -> e.time).thenComparingInt(e -> e.id));
        int first = Math.max(0, events.size() - MAX_TRAFFIC_ROWS);
        long now = SystemClock.uptimeMillis();
        for (int i = first; i < events.size(); i++) {
            final TrafficEvent event = events.get(i);
            // Stagger simultaneous observations without creating an unbounded backlog.
            handler.postAtTime(() -> showTrafficEvent(event), trafficToken,
                    now + (i - first) * TRAFFIC_STAGGER_MS);
        }
    }

    // The upstream connection tab uses info as its remote label. Only show a
    // plausible dotted hostname here, not arbitrary protocol metadata or an IP.
    // This is already captured information: no DNS lookup is performed.
    private static String domainLabel(ConnectionDescriptor conn) {
        String host = conn.info == null ? "" : conn.info.trim();
        if (host.length() > 254 || host.equalsIgnoreCase(conn.dst_ip)) return "";
        return HOST_NAME.matcher(host).matches() ? host : "";
    }

    private static String protocolLabel(ConnectionDescriptor conn) {
        String transport;
        switch (conn.ipproto) {
            case 6: transport = "TCP"; break;
            case 17: transport = "UDP"; break;
            case 1: transport = "ICMP"; break;
            case 58: transport = "ICMPv6"; break;
            default: transport = "IP/" + conn.ipproto; break;
        }
        String app = conn.l7proto == null ? "" : conn.l7proto.trim();
        if (app.isEmpty() || app.equalsIgnoreCase("unknown") || app.equalsIgnoreCase(transport))
            return transport;
        return transport + "/" + app;
    }

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
        resetWatch();
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
