# Auto Hotspot Off (热点自动关)

[ **English** | [中文](README.zh.md) ]

[![AI-DECLARATION: auto](https://img.shields.io/badge/䷼%20AI--DECLARATION-auto-ede9fe?labelColor=ede9fe)](https://ai-declaration.md)
[![breakdown: copilot](https://img.shields.io/badge/breakdown-copilot-fee2e2?labelColor=fee2e2)](AI-DECLARATION.md)
[![design: copilot](https://img.shields.io/badge/design-copilot-fee2e2?labelColor=fee2e2)](AI-DECLARATION.md)
[![testing: copilot](https://img.shields.io/badge/testing-copilot-fee2e2?labelColor=fee2e2)](AI-DECLARATION.md)
[![implementation: auto](https://img.shields.io/badge/implementation-auto-ede9fe?labelColor=ede9fe)](AI-DECLARATION.md)
[![review: auto](https://img.shields.io/badge/review-auto-ede9fe?labelColor=ede9fe)](AI-DECLARATION.md)

An Android app that automatically **turns off your Wi-Fi hotspot when you connect to selected Wi-Fi networks**, and (optionally) **turns it back on when you leave Wi-Fi and switch to mobile data**.

Requirements: Android 12+ (tested on Android 17), with [Shizuku](https://shizuku.rikka.app/) installed and running.

## Features

- **Wi-Fi list**: add the current Wi-Fi with one tap, type an SSID manually, or delete entries.
- **Auto off**: connecting to a listed Wi-Fi turns the hotspot off.
- **Auto on** (optional): switching from Wi-Fi to mobile data turns the hotspot on. It only reacts to the actual switch, so turning the hotspot off by hand while on mobile data won't turn it back on.
- **Status card**: current network (Wi-Fi name / mobile data + carrier), hotspot state including "starting…" / "stopping…", live updates.
- **Checks & hints** for every required permission and setting, a failure notification (e.g. when Shizuku isn't running), and an in-app log.
- No foreground service, no persistent notification; restores itself after reboot and app updates.

## How it works

- **Turning the hotspot on/off**: regular apps can't do this on Android 16+ (`TetheringManager.stopTethering` is silently ignored). The app uses Shizuku to call `ITetheringConnector.start/stopTethering` as the shell user, which holds `TETHER_PRIVILEGED`.
- **Detecting "Wi-Fi connected"**: `ConnectivityManager.registerNetworkCallback(request, PendingIntent)` — the system wakes the app with a broadcast. Reading the SSID requires precise location plus background location ("Allow all the time").
  The system releases this registration ~5 s after each delivery, so it is re-armed in a hand-off (see next point).
- **Detecting "Wi-Fi dropped, now on mobile data"**: a JobScheduler job that requires a cellular network. Job network constraints are evaluated against the app's *default* network, so the job can't run on Wi-Fi and runs as soon as the default network switches to cellular. The job then re-registers the Wi-Fi listener. A 15-minute periodic job is a fallback.
  This requires the app's **background data** not to be restricted — otherwise, with the screen off, cellular counts as unavailable for the app and the job never runs.
- **Why no foreground service**: Shizuku only pushes its binder to an app when the app's uid starts or leaves the cached state. A permanent foreground service keeps the uid always active, so if Shizuku starts later than the app (e.g. after boot), the app would never receive the binder.

## Build & install

```bash
# Requires JDK 17 and the Android SDK (compileSdk 36)
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
# Optional: grant permissions via adb (you can also tap "Grant" in the app)
adb shell pm grant dev.autohotspot android.permission.ACCESS_FINE_LOCATION
adb shell pm grant dev.autohotspot android.permission.ACCESS_BACKGROUND_LOCATION
adb shell pm grant dev.autohotspot android.permission.POST_NOTIFICATIONS
```

Then, in the app: authorize Shizuku, add your Wi-Fi networks, and turn on the switches.

## Debugging (debug builds only)

```bash
D="adb shell am broadcast -n dev.autohotspot/.DebugReceiver -a"
$D state    # print hotspot / Shizuku state
$D sstart   # turn the hotspot on via Shizuku
$D stop     # turn the hotspot off
$D enable / disable
adb logcat -s AutoHotspot
```

The debug receiver requires the `DUMP` permission, so only adb shell can trigger it.

## Notes

- App info → Mobile data & Wi-Fi → **Background data** must be on (the app checks and warns). Via adb: `adb shell cmd netpolicy remove restrict-background-blacklist <uid>`.
- Setting battery usage to **Unrestricted** is recommended; **Restricted** stops background detection entirely.
- Without root, **Shizuku must be started again after every reboot**. Shizuku's "start on boot (wireless debugging)" option can do this, but wireless debugging needs a Wi-Fi connection. If Shizuku isn't running, the app posts a notification when an action fails.
- After a reboot, the system can take a few minutes to deliver the boot broadcast; Wi-Fi switches before that are not handled.
- Starting the hotspot takes about 3 s inside the system (Wi-Fi chip bring-up); the app shows "starting…" meanwhile.
- With the hotspot on and weak signal, the system may not rejoin Wi-Fi by itself, so the app won't be triggered either.

## AI declaration

This project was built with an AI coding agent. See [AI-DECLARATION.md](AI-DECLARATION.md) for the per-stage breakdown.
