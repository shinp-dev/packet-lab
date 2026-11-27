#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Prepare the pinned upstream source and apply Packet Lab's overlay changes."""
import argparse
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
TARGET = ROOT / "packet-lab-android"
UPSTREAM = "https://github.com/emanuele-f/PCAPdroid.git"
REV = "692fd337bd6b622f5fdc5e042dcb14d95faa59b6"
JAVA = "app/src/main/java/com/emanuelef/remote_capture"


def run(*args, cwd=ROOT):
    subprocess.run([str(a) for a in args], cwd=cwd, check=True)


def patch(path, changes):
    p = TARGET / path
    data = p.read_text(encoding="utf-8")
    for before, after in changes:
        if after in data:
            continue
        if data.count(before) != 1:
            raise RuntimeError(f"Unexpected upstream source in {path}: {before[:100]!r}")
        data = data.replace(before, after, 1)
    p.write_text(data, encoding="utf-8")


def prepare(skip_submodules=False):
    if not TARGET.exists():
        run("git", "clone", "--no-checkout", UPSTREAM, TARGET)
        run("git", "checkout", "--detach", REV, cwd=TARGET)
    head = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=TARGET, text=True).strip()
    if head != REV:
        raise RuntimeError("Existing source directory is not at the pinned revision. Move it aside; not resetting user edits.")
    if not skip_submodules:
        run("git", "submodule", "update", "--init", "--recursive", cwd=TARGET)

    patch("app/build.gradle", [
        ('applicationId "com.emanuelef.remote_capture"', 'applicationId "com.shinpstudio.packetlab"'),
    ])
    patch("app/src/main/res/values/strings.xml", [
        ('<string name="pcapdroid_app_name" translatable="false">PCAPdroid</string>',
         '<string name="pcapdroid_app_name" translatable="false">Packet Lab</string>'),
    ])
    patch("app/src/main/AndroidManifest.xml", [
        ('<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED"/>',
         '<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW"/>'),
        ('android:allowBackup="true"', 'android:allowBackup="false"'),
        ('android:icon="@mipmap/ic_launcher"', 'android:icon="@drawable/packet_lab_icon"'),
        ('android:roundIcon="@mipmap/ic_launcher_round"', 'android:roundIcon="@drawable/packet_lab_icon"'),
        ('android:name=".activities.CaptureCtrl"\n            android:theme="@style/AppTheme.Floating"\n            android:launchMode="singleTop"\n            android:exported="true" />',
         'android:name=".activities.CaptureCtrl"\n            android:theme="@style/AppTheme.Floating"\n            android:launchMode="singleTop"\n            android:exported="false" />'),
        ('        <receiver\n            android:name=".BootReceiver"\n            android:exported="false">\n            <intent-filter>\n                <action android:name="android.intent.action.BOOT_COMPLETED"/>\n                <action android:name="android.intent.action.QUICKBOOT_POWERON"/>\n            </intent-filter>\n        </receiver>',
         '        <activity android:name=".PacketCaptureActivity" android:exported="false"\n            android:theme="@style/AppTheme.Floating" android:excludeFromRecents="true"\n            android:taskAffinity="${applicationId}.captureConsent" />\n        <service android:name=".PacketOverlayService" android:exported="false"\n            android:foregroundServiceType="specialUse">\n            <property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"\n                android:value="User-visible floating capture controls"/>\n        </service>'),
        ('            <intent-filter>\n                <action android:name="android.net.VpnService" />',
         '            <meta-data android:name="android.net.VpnService.SUPPORTS_ALWAYS_ON" android:value="false"/>\n            <intent-filter>\n                <action android:name="android.net.VpnService" />'),
    ])

    main = JAVA + "/activities/MainActivity.java"
    patch(main, [
        ('setTitle("PCAPdroid");', 'setTitle("Packet Lab");'),
        ('        setupTabs();', '        setupTabs();\n        showPacketLabResults(getIntent());'),
        ('            String pcapUri = intent.getStringExtra(CaptureListActivity.OPEN_PCAP_EXTRA);',
         '            showPacketLabResults(intent);\n            String pcapUri = intent.getStringExtra(CaptureListActivity.OPEN_PCAP_EXTRA);'),
        ('    private void setupNavigationDrawer() {',
         '    private void showPacketLabResults(Intent intent) {\n        if (mPager != null && intent != null && intent.getBooleanExtra("packet_lab_results", false))\n            mPager.setCurrentItem(POS_CONNECTIONS, false);\n    }\n\n    private void setupNavigationDrawer() {'),
        ('        getMenuInflater().inflate(R.menu.main_activity_menu, menu);',
         '        getMenuInflater().inflate(R.menu.main_activity_menu, menu);\n        menu.add(0, 0x504c0001, 100, "操作パネルを表示");\n        menu.add(0, 0x504c0002, 101, "記録モード設定");'),
        ('        if(id == R.id.action_start) {',
         '        if (id == 0x504c0001) {\n            com.emanuelef.remote_capture.PacketOverlayService.show(this);\n            return true;\n        } else if (id == 0x504c0002) {\n            com.emanuelef.remote_capture.PacketLabConfig.chooseMode(this);\n            return true;\n        } else if(id == R.id.action_start) {'),
        ('        CaptureSettings settings = new CaptureSettings(this, mPrefs);\n        settings.input_pcap_path = input_pcap_path;',
         '        CaptureSettings settings = input_pcap_path == null\n                ? com.emanuelef.remote_capture.PacketLabConfig.localSettings(this)\n                : new CaptureSettings(this, mPrefs);\n        settings.input_pcap_path = input_pcap_path;'),
    ])
    # Independent, idempotent additions also work on a previously prepared checkout.
    patch(main, [
        ('        menu.add(0, 0x504c0002, 101, "記録モード設定");',
         '        menu.add(0, 0x504c0002, 101, "記録モード設定");\n        menu.add(0, 0x504c0003, 102, "通信通知するアプリ");'),
        ('        if (id == 0x504c0001) {',
         '        if (id == 0x504c0003) {\n            com.emanuelef.remote_capture.PacketLabConfig.chooseWatch(this);\n            return true;\n        }\n        if (id == 0x504c0001) {'),
    ])
    # MainActivity's normal start action uses the same local-only policy as the overlay.
    p = TARGET / main
    data = p.read_text(encoding="utf-8")
    marker = "    public void startCapture() {"
    end = "    public void stopCapture() {"
    first, last = data.index(marker), data.index(end)
    new_start = """    public void startCapture() {
        VpnReconnectService.stopService();
        if (CaptureService.isServiceActive() ||
                com.emanuelef.remote_capture.PacketOverlayService.isStartPending()) return;
        if (Utils.getRunningVpn(this) != null) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.active_vpn_detected)
                    .setMessage(R.string.disconnect_vpn_confirm)
                    .setPositiveButton(R.string.ok, (d, w) -> doStartCaptureService(null))
                    .setNegativeButton(R.string.cancel_action, (d, w) -> {})
                    .show();
        } else
            doStartCaptureService(null);
    }

"""
    p.write_text(data[:first] + new_start + data[last:], encoding="utf-8")

    patch(JAVA + "/CaptureHelper.java", [
        ('        ContextCompat.startForegroundService(mContext, intent);\n        if(mListener != null)\n            mListener.onCaptureStartResult(true);',
         '        if (mContext instanceof PacketCaptureActivity && !PacketOverlayService.isStartPending()) {\n            captureStartFailed();\n            return;\n        }\n        try {\n            ContextCompat.startForegroundService(mContext, intent);\n        } catch (RuntimeException e) {\n            Log.e(TAG, "Cannot start capture service", e);\n            captureStartFailed();\n            return;\n        }\n        if(mListener != null)\n            mListener.onCaptureStartResult(true);'),
    ])
    for p in (ROOT / "overlay-src").glob("*.java"):
        shutil.copyfile(p, TARGET / JAVA / p.name)
    shutil.copyfile(ROOT / "overlay-src/packet_lab_icon.xml",
                    TARGET / "app/src/main/res/drawable/packet_lab_icon.xml")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--build", action="store_true")
    parser.add_argument("--skip-submodules", action="store_true", help="Source-patch checking only")
    args = parser.parse_args()
    if args.build and args.skip_submodules:
        parser.error("--build requires submodules")
    prepare(args.skip_submodules)
    if args.build:
        if sys.platform == "win32":
            run("cmd", "/c", "gradlew.bat", ":app:assembleStandardDebug", cwd=TARGET)
        else:
            run("bash", "./gradlew", ":app:assembleStandardDebug", cwd=TARGET)
    print("Packet Lab source ready:", TARGET)
