package com.svenuks.imsforpixel

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.PersistableBundle
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties

object WfcMode {
    const val DEFAULT = -1
    const val WIFI_ONLY = 0
    const val CELLULAR_PREFERRED = 1
    const val WIFI_PREFERRED = 2

    fun label(mode: Int): String = when (mode) {
        WIFI_ONLY -> "Wi-Fi Only"
        CELLULAR_PREFERRED -> "Cellular Preferred"
        WIFI_PREFERRED -> "Wi-Fi Preferred"
        else -> "Carrier Default"
    }
}

enum class SimPreset {
    TRAVEL_BACKUP,
    UNSUPPORTED_FULL,
    VOLTE_VONR_ONLY,
    MINIMAL_VOLTE
}

data class SimOverrideConfig(
    val overrideEnabled: Boolean = false,
    // Voice & 5G
    val volte: Boolean = true,
    val vonr: Boolean = false,
    val nrSaUnlock: Boolean = false,
    // Wi-Fi & Backup Calling
    val vowifi: Boolean = true,
    val wfcRoaming: Boolean = true,
    val crossSim: Boolean = true,
    val wfcMode: Int = WfcMode.DEFAULT,
    val wfcRoamingMode: Int = WfcMode.DEFAULT,
    // Carrier / Advanced tweaks (non-destructive, opt-in)
    val ssUt: Boolean = false,
    val carrierAggregation: Boolean = false,
    val vilte: Boolean = false,
    val smsOverIms: Boolean = false,
    val imsCallFeatures: Boolean = false,
    val showImsStatus: Boolean = false,
    val show4gIcon: Boolean = false,
    val allowApnEdit: Boolean = false,
    val protect2g: Boolean = false,
    val autoDataSwitch: Boolean = false
)

data class SimLiveStatus(
    val slotIndex: Int,
    val subId: Int = -1,
    val carrierName: String = "",
    val isEmbedded: Boolean = false,
    val mccMnc: String = "",
    val imsRegistered: Boolean = false,
    val imsTransport: String = "NONE",
    val crossSimEnabled: Boolean = false,
    val wfcEnabled: Boolean = false,
    val configApplied: Boolean = false,
    val carrierConfigVersion: String = "",
    val overrideCount: Int = 0,
    val apnConflictDetail: String = "",
    val apnImsPresent: Boolean = true,
    val lastUpdatedEpochMs: Long = 0L
)

object SimConfigStore {
    const val PREFS_NAME = "volte_settings"
    private const val INITIALIZED_KEY = "initialized_fork_v1"
    private const val BOOT_REMINDER_KEY = "boot_reminder_enabled"
    private const val LAST_LOG_KEY = "last_execution_log"

    fun ensureDefaults(prefs: SharedPreferences) {
        if (prefs.getBoolean(INITIALIZED_KEY, false)) return
        val editor = prefs.edit()
        for (slot in 0..1) {
            if (!prefs.contains("override_enabled_slot_$slot")) {
                saveSimConfig(editor, slot, SimOverrideConfig())
            }
        }
        if (!prefs.contains(BOOT_REMINDER_KEY)) {
            editor.putBoolean(BOOT_REMINDER_KEY, true)
        }
        editor.putBoolean(INITIALIZED_KEY, true)
        editor.apply()
    }

    fun isBootReminderEnabled(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(BOOT_REMINDER_KEY, true)

    fun setBootReminderEnabled(prefs: SharedPreferences, enabled: Boolean) {
        prefs.edit().putBoolean(BOOT_REMINDER_KEY, enabled).apply()
    }

    fun getLastExecutionLog(prefs: SharedPreferences): String =
        prefs.getString(LAST_LOG_KEY, "") ?: ""

    fun setLastExecutionLog(prefs: SharedPreferences, log: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val trimmed = log.trim().take(4000)
        prefs.edit().putString(LAST_LOG_KEY, "[$timestamp]\n$trimmed").apply()
    }

    fun applyPreset(preset: SimPreset): SimOverrideConfig {
        return when (preset) {
            SimPreset.TRAVEL_BACKUP -> SimOverrideConfig(
                overrideEnabled = true,
                volte = true,
                vonr = false,
                nrSaUnlock = false,
                vowifi = true,
                wfcRoaming = true,
                crossSim = true,
                wfcMode = WfcMode.WIFI_PREFERRED,
                wfcRoamingMode = WfcMode.WIFI_PREFERRED,
                ssUt = false,
                smsOverIms = true,
                showImsStatus = true,
                autoDataSwitch = true
            )
            SimPreset.UNSUPPORTED_FULL -> SimOverrideConfig(
                overrideEnabled = true,
                volte = true,
                vonr = true,
                nrSaUnlock = true,
                vowifi = true,
                wfcRoaming = true,
                crossSim = true,
                wfcMode = WfcMode.WIFI_PREFERRED,
                wfcRoamingMode = WfcMode.WIFI_PREFERRED,
                ssUt = true,
                carrierAggregation = true,
                vilte = true,
                smsOverIms = true,
                imsCallFeatures = true,
                showImsStatus = true
            )
            SimPreset.VOLTE_VONR_ONLY -> SimOverrideConfig(
                overrideEnabled = true,
                volte = true,
                vonr = true,
                nrSaUnlock = true,
                vowifi = false,
                wfcRoaming = false,
                crossSim = false,
                wfcMode = WfcMode.DEFAULT,
                wfcRoamingMode = WfcMode.DEFAULT,
                ssUt = false,
                showImsStatus = true
            )
            SimPreset.MINIMAL_VOLTE -> SimOverrideConfig(
                overrideEnabled = true,
                volte = true,
                vonr = false,
                vowifi = false,
                wfcRoaming = false,
                crossSim = false,
                wfcMode = WfcMode.DEFAULT,
                wfcRoamingMode = WfcMode.DEFAULT,
                ssUt = false
            )
        }
    }

    fun loadSimConfig(prefs: SharedPreferences, slot: Int): SimOverrideConfig {
        return SimOverrideConfig(
            overrideEnabled = prefs.getBoolean("override_enabled_slot_$slot", false),
            volte = prefs.getBoolean("volte_slot_$slot", true),
            vonr = prefs.getBoolean("vonr_slot_$slot", false),
            nrSaUnlock = prefs.getBoolean("nr_sa_unlock_slot_$slot", false),
            vowifi = prefs.getBoolean("vowifi_slot_$slot", true),
            wfcRoaming = prefs.getBoolean("wfc_roaming_slot_$slot", true),
            crossSim = prefs.getBoolean("cross_sim_slot_$slot", true),
            wfcMode = prefs.getInt("wfc_mode_slot_$slot", WfcMode.DEFAULT),
            wfcRoamingMode = prefs.getInt("wfc_roaming_mode_slot_$slot", WfcMode.DEFAULT),
            ssUt = prefs.getBoolean("ss_ut_slot_$slot", false),
            carrierAggregation = prefs.getBoolean("ca_lte_plus_slot_$slot", false),
            vilte = prefs.getBoolean("vilte_slot_$slot", false),
            smsOverIms = prefs.getBoolean("sms_ims_slot_$slot", false),
            imsCallFeatures = prefs.getBoolean("ims_call_features_slot_$slot", false),
            showImsStatus = prefs.getBoolean("show_ims_slot_$slot", false),
            show4gIcon = prefs.getBoolean("show_4g_icon_slot_$slot", false),
            allowApnEdit = prefs.getBoolean("allow_apn_slot_$slot", false),
            protect2g = prefs.getBoolean("protect_2g_slot_$slot", false),
            autoDataSwitch = prefs.getBoolean("auto_data_switch_slot_$slot", false)
        )
    }

    fun saveSimConfig(prefs: SharedPreferences, slot: Int, config: SimOverrideConfig, commit: Boolean = false) {
        val editor = prefs.edit()
        saveSimConfig(editor, slot, config)
        if (commit) editor.commit() else editor.apply()
    }

    private fun saveSimConfig(editor: SharedPreferences.Editor, slot: Int, config: SimOverrideConfig) {
        editor
            .putBoolean("override_enabled_slot_$slot", config.overrideEnabled)
            .putBoolean("volte_slot_$slot", config.volte)
            .putBoolean("vonr_slot_$slot", config.vonr)
            .putBoolean("nr_sa_unlock_slot_$slot", config.nrSaUnlock)
            .putBoolean("vowifi_slot_$slot", config.vowifi)
            .putBoolean("wfc_roaming_slot_$slot", config.wfcRoaming)
            .putBoolean("cross_sim_slot_$slot", config.crossSim)
            .putInt("wfc_mode_slot_$slot", config.wfcMode)
            .putInt("wfc_roaming_mode_slot_$slot", config.wfcRoamingMode)
            .putBoolean("ss_ut_slot_$slot", config.ssUt)
            .putBoolean("ca_lte_plus_slot_$slot", config.carrierAggregation)
            .putBoolean("vilte_slot_$slot", config.vilte)
            .putBoolean("sms_ims_slot_$slot", config.smsOverIms)
            .putBoolean("ims_call_features_slot_$slot", config.imsCallFeatures)
            .putBoolean("show_ims_slot_$slot", config.showImsStatus)
            .putBoolean("show_4g_icon_slot_$slot", config.show4gIcon)
            .putBoolean("allow_apn_slot_$slot", config.allowApnEdit)
            .putBoolean("protect_2g_slot_$slot", config.protect2g)
            .putBoolean("auto_data_switch_slot_$slot", config.autoDataSwitch)
    }

    /**
     * Non-destructive CarrierConfig bundle: only enabled toggles (or explicit WFC modes)
     * are populated so disabled features preserve the carrier's stock Pixel config.
     */
    fun buildCarrierConfigBundle(config: SimOverrideConfig): PersistableBundle {
        val bundle = PersistableBundle()

        if (config.volte) {
            bundle.putBoolean("carrier_volte_available_bool", true)
            bundle.putBoolean("imsvoice.carrier_volte_roaming_available_bool", true)
            bundle.putBoolean("carrier_default_volte_roaming_enabled_bool", true)
            bundle.putBoolean("enhanced_4g_lte_on_by_default_bool", true)
            bundle.putBoolean("hide_enhanced_4g_lte_bool", false)
            bundle.putBoolean("editable_enhanced_4g_lte_bool", true)
            bundle.putBoolean("carrier_volte_provisioned_bool", true)
            bundle.putBoolean("carrier_volte_provisioning_required_bool", false)
        }

        if (config.vonr) {
            bundle.putBoolean("vonr_enabled_bool", true)
            bundle.putBoolean("vonr_setting_visibility_bool", true)
            bundle.putBoolean("vonr_on_by_default_bool", true)
        }

        if (config.nrSaUnlock) {
            bundle.putIntArray("carrier_nr_availabilities_int_array", intArrayOf(1, 2))
        }

        if (config.vowifi) {
            bundle.putBoolean("carrier_wfc_ims_available_bool", true)
            bundle.putBoolean("carrier_wfc_ims_roaming_available_bool", true)
            bundle.putBoolean("carrier_default_wfc_ims_enabled_bool", true)
            bundle.putBoolean("carrier_wfc_ims_provisioned_bool", true)
            bundle.putBoolean("carrier_volte_override_wfc_provisioning_bool", true)
            bundle.putBoolean("carrier_promote_wfc_on_call_fail_bool", true)
            bundle.putBoolean("editable_wfc_mode_bool", true)
            bundle.putBoolean("editable_wfc_roaming_mode_bool", true)
            bundle.putBoolean("imsserviceentitlement.ims_provisioning_bool", false)
            bundle.putBoolean("imsserviceentitlement.skip_wfc_activation_bool", true)
        }

        if (config.wfcRoaming) {
            bundle.putBoolean("carrier_default_wfc_ims_roaming_enabled_bool", true)
            bundle.putBoolean("use_wfc_home_network_mode_in_roaming_network_bool", true)
        }

        if (config.wfcMode != WfcMode.DEFAULT) {
            bundle.putInt("carrier_default_wfc_ims_mode_int", config.wfcMode)
            if (config.wfcMode == WfcMode.WIFI_ONLY) {
                bundle.putBoolean("carrier_wfc_supports_wifi_only_bool", true)
            }
        }

        if (config.wfcRoamingMode != WfcMode.DEFAULT) {
            bundle.putInt("carrier_default_wfc_ims_roaming_mode_int", config.wfcRoamingMode)
            if (config.wfcRoamingMode == WfcMode.WIFI_ONLY) {
                bundle.putBoolean("carrier_wfc_supports_wifi_only_bool", true)
            }
        }

        if (config.crossSim) {
            bundle.putBoolean("carrier_cross_sim_ims_available_bool", true)
            bundle.putBoolean("enable_cross_sim_calling_on_opportunistic_data_bool", true)
            bundle.putInt("cross_sim_spn_format_int", 1)
        }

        if (config.ssUt) {
            bundle.putBoolean("carrier_supports_ss_over_ut_bool", true)
            bundle.putBoolean("carrier_ut_provisioning_required_bool", false)
        }

        if (config.carrierAggregation) {
            bundle.putInt("lte_plus_threshold_bandwidth_khz_int", 0)
            bundle.putBoolean("hide_lte_plus_data_icon_bool", false)
        }

        if (config.vilte) {
            bundle.putBoolean("carrier_vt_available_bool", true)
        }

        if (config.smsOverIms) {
            bundle.putBoolean("imssms.sms_over_ims_supported_bool", true)
        }

        if (config.imsCallFeatures) {
            bundle.putBoolean("carrier_allow_transfer_ims_call_bool", true)
            bundle.putBoolean("carrier_allow_deflect_ims_call_bool", true)
            bundle.putBoolean("support_ims_conference_call_bool", true)
        }

        if (config.showImsStatus) {
            bundle.putBoolean("show_ims_registration_status_bool", true)
            bundle.putBoolean("display_hd_plus_icon_bool", true)
        }

        if (config.show4gIcon) {
            bundle.putBoolean("show_4g_for_lte_data_icon_bool", true)
        }

        if (config.allowApnEdit) {
            bundle.putBoolean("allow_adding_apns_bool", true)
            bundle.putBoolean("apn_expand_bool", true)
            bundle.putBoolean("hide_ims_apn_bool", false)
        }

        if (config.protect2g) {
            bundle.putBoolean("carrier_default_2g_protection_enabled_bool", true)
        }

        return bundle
    }

    fun saveLiveStatus(context: Context, status: SimLiveStatus) {
        val slot = status.slotIndex
        try {
            File(context.filesDir, "ims_status_$slot.txt").writeText(status.imsRegistered.toString())
            File(context.filesDir, "config_applied_$slot.txt").writeText(status.configApplied.toString())
            val props = Properties()
            props.setProperty("subId", status.subId.toString())
            props.setProperty("carrierName", status.carrierName)
            props.setProperty("isEmbedded", status.isEmbedded.toString())
            props.setProperty("mccMnc", status.mccMnc)
            props.setProperty("imsRegistered", status.imsRegistered.toString())
            props.setProperty("imsTransport", status.imsTransport)
            props.setProperty("crossSimEnabled", status.crossSimEnabled.toString())
            props.setProperty("wfcEnabled", status.wfcEnabled.toString())
            props.setProperty("configApplied", status.configApplied.toString())
            props.setProperty("carrierConfigVersion", status.carrierConfigVersion)
            props.setProperty("overrideCount", status.overrideCount.toString())
            props.setProperty("apnConflictDetail", status.apnConflictDetail)
            props.setProperty("apnImsPresent", status.apnImsPresent.toString())
            props.setProperty("lastUpdatedEpochMs", status.lastUpdatedEpochMs.toString())
            File(context.filesDir, "sim_status_$slot.properties").outputStream().use {
                props.store(it, null)
            }
        } catch (_: Exception) {
        }
    }

    fun loadLiveStatus(context: Context, slot: Int): SimLiveStatus {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val fallbackReg = try {
            File(context.filesDir, "ims_status_$slot.txt").readText().trim().toBoolean()
        } catch (_: Exception) {
            prefs.getBoolean("ims_registered_slot_$slot", false)
        }
        val fallbackApplied = try {
            File(context.filesDir, "config_applied_$slot.txt").readText().trim().toBoolean()
        } catch (_: Exception) {
            false
        }
        val propsFile = File(context.filesDir, "sim_status_$slot.properties")
        if (!propsFile.exists()) {
            return SimLiveStatus(slotIndex = slot, imsRegistered = fallbackReg, configApplied = fallbackApplied)
        }
        return try {
            val props = Properties()
            propsFile.inputStream().use { props.load(it) }
            SimLiveStatus(
                slotIndex = slot,
                subId = props.getProperty("subId")?.toIntOrNull() ?: -1,
                carrierName = props.getProperty("carrierName") ?: "",
                isEmbedded = props.getProperty("isEmbedded")?.toBoolean() ?: false,
                mccMnc = props.getProperty("mccMnc") ?: "",
                imsRegistered = props.getProperty("imsRegistered")?.toBoolean() ?: fallbackReg,
                imsTransport = props.getProperty("imsTransport") ?: if (fallbackReg) "REGISTERED" else "NONE",
                crossSimEnabled = props.getProperty("crossSimEnabled")?.toBoolean() ?: false,
                wfcEnabled = props.getProperty("wfcEnabled")?.toBoolean() ?: false,
                configApplied = props.getProperty("configApplied")?.toBoolean() ?: fallbackApplied,
                carrierConfigVersion = props.getProperty("carrierConfigVersion") ?: "",
                overrideCount = props.getProperty("overrideCount")?.toIntOrNull() ?: 0,
                apnConflictDetail = props.getProperty("apnConflictDetail") ?: "",
                apnImsPresent = props.getProperty("apnImsPresent")?.toBoolean() ?: true,
                lastUpdatedEpochMs = props.getProperty("lastUpdatedEpochMs")?.toLongOrNull() ?: 0L
            )
        } catch (_: Exception) {
            SimLiveStatus(slotIndex = slot, imsRegistered = fallbackReg, configApplied = fallbackApplied)
        }
    }

    fun buildDiagnosticReport(context: Context, s0: SimLiveStatus, s1: SimLiveStatus): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return buildString {
            appendLine("# TensorIMS Diagnostic Report")
            appendLine("- Device: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, SDK ${Build.VERSION.SDK_INT})")
            appendLine("- App Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine()
            for (s in listOf(s0, s1)) {
                val cfg = loadSimConfig(prefs, s.slotIndex)
                val typeLabel = if (s.isEmbedded) "eSIM" else "pSIM"
                appendLine("## SIM ${s.slotIndex + 1} (${s.carrierName.ifBlank { "Unknown" }} · $typeLabel)")
                appendLine("- SubID: ${s.subId} | MCC/MNC: ${s.mccMnc.ifBlank { "-" }}")
                appendLine("- CarrierConfig Version: ${s.carrierConfigVersion.ifBlank { "-" }}")
                appendLine("- Override Active: ${s.configApplied} (master=${cfg.overrideEnabled}, keys=${s.overrideCount})")
                appendLine("- IMS Registered: ${s.imsRegistered} (${s.imsTransport})")
                appendLine("- VoWiFi Setting: ${s.wfcEnabled} | Backup Calling: ${s.crossSimEnabled}")
                appendLine("- APN Health: imsPresent=${s.apnImsPresent}, conflict=${s.apnConflictDetail.ifBlank { "none" }}")
                appendLine()
            }
        }
    }
}
