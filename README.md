# TensorIMS

> **Rootless Material You toolkit for Google Pixel (Android 10–17): per-SIM VoLTE, VoWiFi, working Backup Calling (Cross-SIM), 5G VoNR, Quick Settings re-apply, smart presets, and APN diagnostics.**

[![Platform](https://img.shields.io/badge/Platform-Android%2010--17-10B981.svg)](https://developer.android.com)
[![API](https://img.shields.io/badge/API-28%2B-38BDF8.svg)](https://developer.android.com)
[![UI](https://img.shields.io/badge/UI-Material%20You%203-60A5FA.svg)](https://m3.material.io)
[![Release](https://img.shields.io/github/v/release/bipinsubedi/TensorIMS?color=10B981)](https://github.com/bipinsubedi/TensorIMS/releases)
[![License: MIT](https://img.shields.io/badge/License-MIT-F59E0B.svg)](LICENSE)

---

## Why TensorIMS?

Forked and substantially re-engineered from [`svenuks/ImsForPixel`](https://github.com/svenuks/ImsForPixel), **TensorIMS** fixes the dual-SIM and Android 14–17 regressions that break classic Pixel IMS and earlier tools:

| Capability | Classic Pixel IMS | Original `ImsForPixel` | **TensorIMS (v2.1.0)** |
|---|---|---|---|
| **Root / Shizuku Required** | ⚠️ Requires Shizuku | ❌ Standalone | ❌ **Standalone (Zero external apps)** |
| **Per-SIM Isolation** | ⚠️ Manual per-SIM | ❌ Overwrites & resets **both** SIMs | ✅ **True Per-SIM Master Switch & Non-Destructive Overrides** |
| **Backup Calling (Cross-SIM)** | ⚠️ CarrierConfig only (broken on Pixel Android 14+) | ❌ Hardcoded OFF (`false`) | ✅ **CarrierConfig + `ImsMmTelManager.setCrossSimCallingEnabled()` + Readiness Check** |
| **APN Conflict Detection** | ❌ None | ❌ None | ✅ **Detects & 1-tap fixes `apn=internet` `,ims` ePDG ERR 24 loops** |
| **Post-Reboot Re-Apply** | ⚠️ Shizuku needed | ❌ Manual every reboot | ✅ **Quick Settings Tile + 90s Auto-Apply Watcher + Boot Actions + Auto-Launch Apply** |
| **Banking-App Friendly** | ⚠️ Manual toggle | ⚠️ Leaves Wireless Debug ON | ✅ **Optional Auto-Disable Wireless Debugging after apply** |
| **WFC Mode & Smart Presets** | ❌ None | ❌ None | ✅ **Home/Roaming WFC Modes + 4 1-Tap Presets + Custom Key Editor** |
| **Profiles & Diagnostics** | ❌ None | ⚠️ Basic boolean | ✅ **Real Transport (`VoWiFi` / `CROSS_SIM` / `LTE` / `NR`), Key Diff, JSON Export/Import** |
| **UI & Theming** | Legacy XML | Single scrolling card | ✅ **Decluttered 3-Tab Material You (Dynamic Color + Edge-to-Edge)** |

---

## Key Features

### 1. True Per-SIM Isolation (Never Clobber Your Working SIM)
Running a dual-SIM setup where **SIM 1** needs overrides (e.g., roaming SIM in an unsupported region) while **SIM 2** already has a native Google Pixel carrier bundle (e.g., NTT docomo / ahamo, SoftBank, Verizon, T-Mobile)?
- Each SIM has an independent **Override Carrier Config** master switch (default **OFF**).
- Untouched SIMs are **never** modified and **never** hit with `TelephonyManager.resetIms()`.
- **Non-destructive bundles:** Toggling a feature OFF omits its keys rather than writing `false`, and applying always clears stale merged overrides first.

### 2. Real Backup Calling (Cross-SIM IMS) on Android 14–17
On modern Pixel devices (Pixel 6–10 series), Google hid the Backup Calling toggle in Settings and `CarrierConfigManager` alone does not activate it:
- **TensorIMS** injects `carrier_cross_sim_ims_available_bool` + `enable_cross_sim_calling_on_opportunistic_data_bool` **and** invokes `ImsMmTelManager.setCrossSimCallingEnabled(true)` under shell permission identity.
- **4-Point Backup Calling Readiness Check:** Verifies Dual-SIM state, Cross-SIM CarrierConfig, System `crossSimCallingEnabled`, and APN Health in Diagnostics.
- **Built-in APN Health Inspector:** Detects when a non-IMS APN (such as `apn=internet` with `type=default,supl,ims`) causes ePDG IKEv2 `ERR 24` auth failures, and provides a **1-tap Fix APN** action.

### 3. Zero-Friction Post-Reboot Re-Apply
Android 16/17 blocks non-system apps from using `overrideConfig(..., persistent = true)` (causing `com.android.phone` crashes), so overrides reset on reboot. TensorIMS makes re-applying effortless:
- **Quick Settings Tile:** Add **TensorIMS** to your notification shade. After a reboot, tap the tile — it starts a 90-second **Auto-Apply Watcher** and opens Wireless Debugging so flipping one switch re-applies everything automatically.
- **Boot Notification Actions:** Tap **Quick Re-Apply** or **Auto-Apply Watcher** directly from the post-reboot notification.
- **Auto-Apply on Launch:** Opening TensorIMS when Wireless Debugging is active automatically re-applies your saved overrides after a reboot.
- **Auto-Disable Wireless Debugging (Optional):** Automatically turns Wireless Debugging back OFF right after overrides are applied — great for banking apps and security.

### 4. Smart Presets, Custom Keys & Profile Backup
- **1-Tap Smart Presets:** `Travel & Backup`, `Unsupported Full`, `VoLTE + VoNR`, `Minimal VoLTE`.
- **Custom CarrierConfig Key Editor:** Inject any `bool`, `int`, or `string` `CarrierConfigManager` key directly from Advanced Settings.
- **JSON Profile Export/Import & Markdown Report:** Back up your dual-SIM config or copy a sanitized diagnostic snapshot in one tap.

---

## Quick Start

1. **Install TensorIMS:** Download the latest `TensorIMS-v*.apk` from [GitHub Releases](https://github.com/bipinsubedi/TensorIMS/releases).
2. **Pair Once:** Connect to Wi-Fi, tap the **Wireless ADB** pill at the top of **Home** → **Open Wireless Debugging** → **Pair device with pairing code**, and enter the 6-digit code in the notification.
3. **Configure Your SIM(s):** Go to **Configure**, choose your SIM, pick a **Smart Preset** or customize toggles, and tap **Apply**.
4. **Add the Quick Settings Tile (Recommended):** Pull down Quick Settings → Edit → drag **TensorIMS** into your active tiles for 1-tap re-apply after reboots.

---

## Building from Source

```bash
git clone https://github.com/bipinsubedi/TensorIMS.git
cd TensorIMS

# JDK 17 and Android SDK (compileSdk 36) required
./gradlew assembleDebug    # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease  # app/build/outputs/apk/release/app-release.apk
```

---

## Credits & License

- **Maintainer:** [Bipin Subedi](https://github.com/bipinsubedi)
- **Upstream Base:** [`svenuks/ImsForPixel`](https://github.com/svenuks/ImsForPixel)
- **Libraries:** [Kadb](https://github.com/flyfishxu/Kadb), [LSPosed HiddenApiBypass](https://github.com/LSPosed/AndroidHiddenApiBypass)
- Licensed under the [MIT License](LICENSE).
