# TensorIMS

> **Rootless Material You toolkit for Google Pixel (Android 10–17): per-SIM VoLTE, VoWiFi, working Backup Calling (Cross-SIM), 5G VoNR, smart presets, and APN diagnostics.**

[![Platform](https://img.shields.io/badge/Platform-Android%2010--17-10B981.svg)](https://developer.android.com)
[![API](https://img.shields.io/badge/API-28%2B-38BDF8.svg)](https://developer.android.com)
[![UI](https://img.shields.io/badge/UI-Material%20You%203-60A5FA.svg)](https://m3.material.io)
[![License: MIT](https://img.shields.io/badge/License-MIT-F59E0B.svg)](LICENSE)

---

## Why TensorIMS?

Forked and substantially re-engineered from [`svenuks/ImsForPixel`](https://github.com/svenuks/ImsForPixel), **TensorIMS** solves the real-world dual-SIM and Android 14–17 regressions that break older tools like classic Pixel IMS and unpatched forks:

| Capability | Classic Pixel IMS | Original `ImsForPixel` | **TensorIMS** |
|---|---|---|---|
| **Root / Shizuku Required** | ⚠️ Requires Shizuku | ❌ Standalone | ❌ **Standalone (Zero external apps)** |
| **Per-SIM Isolation** | ⚠️ Manual per-SIM | ❌ Overwrites & resets **both** SIMs | ✅ **True Per-SIM Master Switch & Non-Destructive Overrides** |
| **Backup Calling (Cross-SIM)** | ⚠️ CarrierConfig only (broken on Pixel Android 14+) | ❌ Hardcoded OFF (`false`) | ✅ **CarrierConfig + `ImsMmTelManager.setCrossSimCallingEnabled()` + Auto Data Switch** |
| **APN Conflict Detection** | ❌ None | ❌ None | ✅ **Detects & 1-tap fixes `apn=internet` `,ims` ePDG ERR 24 loops** |
| **WFC Mode Selectors** | ❌ None | ❌ None | ✅ **Home & Roaming Mode (`Wi-Fi Pref` / `Cell Pref` / `Wi-Fi Only`)** |
| **1-Tap Smart Presets** | ❌ None | ❌ None | ✅ **Travel & Backup, Unsupported Full, VoLTE+VoNR, Minimal** |
| **Live Transport Diagnostics** | ❌ Basic boolean | ⚠️ Basic boolean (`nohup` race) | ✅ **Real Transport (`VoWiFi` / `CROSS_SIM` / `LTE` / `NR`), SubID, Bundle Version, Report Copy** |
| **Post-Reboot Reminder** | ❌ None | ❌ None | ✅ **Boot notification when overrides are active** |
| **UI & Theming** | Legacy XML | Single scrolling card | ✅ **3-Tab Material You (Dynamic Color + Edge-to-Edge)** |

---

## Key Features

### 1. True Per-SIM Isolation (Never Clobber Your Working SIM)
If you run a dual-SIM setup where **SIM 1** needs overrides (e.g., roaming SIM in an unsupported region) while **SIM 2** already has a native Google Pixel carrier bundle (e.g., NTT docomo / ahamo, SoftBank, Verizon, T-Mobile):
- Each SIM has an independent **Override Carrier Config** master switch (default **OFF**).
- Untouched SIMs are **never** modified and **never** hit with `TelephonyManager.resetIms()`.
- **Non-destructive bundles:** Toggling a feature OFF omits its keys rather than writing `false`, and applying always clears stale merged overrides first.

### 2. Real Backup Calling (Cross-SIM IMS) on Android 14–17
On modern Pixel phones (Pixel 7 / 8 / 9 / 10 series), Google hid the Backup Calling toggle in Settings and `CarrierConfigManager` alone is not enough:
- **TensorIMS** sets both `carrier_cross_sim_ims_available_bool` + `enable_cross_sim_calling_on_opportunistic_data_bool` **and** invokes `ImsMmTelManager.setCrossSimCallingEnabled(true)` under shell permission identity.
- **Built-in APN Health Inspector:** If a non-IMS APN (such as `apn=internet` with `type=default,supl,ims`) hijacks IMS setup over cellular data, the ePDG rejects IKEv2 auth (`ERR 24`, 2-hour backoff). TensorIMS automatically detects this conflict and provides a **1-tap Fix APN** action.

### 3. Smart Presets & Expanded Android 17 Controls
- **1-Tap Presets:** `Travel & Backup`, `Unsupported Full`, `VoLTE + VoNR`, `Minimal VoLTE`.
- **Voice & 5G:** VoLTE (with proper `imsvoice.carrier_volte_roaming_available_bool`), 5G VoNR, **5G SA + NSA unlock** (`[1, 2]`).
- **Wi-Fi & Backup Calling:** VoWiFi, Roaming VoWiFi, Entitlement WebView bypass, Backup Calling, and separate **Home / Roaming WFC Preference** selectors (`Default` / `Wi-Fi Pref` / `Cell Pref` / `Wi-Fi Only`).
- **Advanced Tweaks:** Supplementary Services (UT/XCAP — kept opt-in so USSD carriers like docomo/ahamo aren't broken), LTE+ Carrier Aggregation icon, ViLTE, SMS over IMS, IMS Call Transfer & Conference, Show IMS/HD+ icon, 4G-for-LTE icon, APN editing unlock, 2G Network Protection, and Auto Data Switch policy (`MOBILE_DATA_POLICY_AUTO_DATA_SWITCH`).

### 4. Live Diagnostics & Synchronous Execution
- **Home:** Compact Wireless ADB status pill, Dual-SIM hero cards showing live transport (`VoWiFi (IWLAN)`, `Backup Calling`, `VoLTE`, `5G VoNR`), APN conflict alerts, batch controls, and 1-tap system shortcuts (`SIM & Network`, `Phone Info`, `Wireless Debugging`, `Wi-Fi`).
- **Diagnostics:** Per-SIM SubID, MCC/MNC, active `CarrierConfig` bundle version, override count, live `ImsRegistrationAttributes` transport, last instrumentation log, and **Copy Report** (privacy-safe Markdown).
- **Reboot Reminder:** Optional notification after reboot reminding you to re-apply ephemeral overrides.

---

## Architecture

```
[Material 3 UI / LocalAdbManager]
        │  127.0.0.1 Kadb (mDNS _adb-tls-connect._tcp)
        ▼
[BrokerInstrumentation]  ──► UiAutomation.adoptShellPermissionIdentity()
        │
        ├──► CarrierConfigManager.overrideConfig(subId, null, false)   [clean slate]
        ├──► CarrierConfigManager.overrideConfig(subId, bundle, false) [non-destructive]
        ├──► ImsMmTelManager.setCrossSimCallingEnabled / setVoWiFi*
        ├──► TelephonyManager.setMobileDataPolicyEnabled(AUTO_DATA_SWITCH)
        ├──► TelephonyManager.resetIms()  [ONLY on modified SIM]
        └──► Poll ImsRegistrationAttributes + APN Health
```

---

## Quick Start

1. **Enable Wireless Debugging:** Go to **Settings → System → Developer options** and turn **Wireless debugging** ON (requires Wi-Fi).
2. **Pair Once:** In **TensorIMS → Home**, expand the Wireless ADB banner, tap **Open Wireless Debugging**, tap **Pair device with pairing code**, and enter the 6-digit code in the notification reply action.
3. **Configure Your SIM(s):** Switch to the **Configure** tab, select your SIM, enable **Override Carrier Config** (or pick a **Smart Preset**), and tap **Apply**.
4. **Verify Live Status:** Check the **Home** or **Diagnostics** tab — you should see `IMS: VoWiFi (IWLAN)` or `IMS: Backup Calling` when Wi-Fi is off and secondary SIM data is active.

### Backup Calling Troubleshooting
- **Wi-Fi Calling works, but Backup Calling doesn't register when Wi-Fi is OFF:**
  1. Ensure **Mobile data** is active on your other SIM (and roaming/data is working on it).
  2. Check the **Home** or **Diagnostics** tab for an **APN Conflict** warning — if your carrier's `internet` APN includes `,ims`, tap **Fix APN** (or manually remove `,ims` from the `internet` APN in SIM APN settings so `apn=ims` handles IMS).
  3. Enable **Auto Data Switch Policy** under Advanced settings if needed.

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
