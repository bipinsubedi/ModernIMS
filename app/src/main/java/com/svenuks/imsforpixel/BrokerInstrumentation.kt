package com.svenuks.imsforpixel

import android.app.Instrumentation
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.UiAutomation
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.provider.Telephony
import android.telephony.AccessNetworkConstants
import android.telephony.CarrierConfigManager
import android.telephony.NetworkRegistrationInfo
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.telephony.ims.ImsManager
import android.telephony.ims.ImsReasonInfo
import android.telephony.ims.ImsRegistrationAttributes
import android.telephony.ims.RegistrationManager
import android.util.Log
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.File
import java.lang.reflect.Method
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class BrokerInstrumentation : Instrumentation() {
    companion object {
        private const val TAG = "VoLTEBrokerInst"
        private const val MOBILE_DATA_POLICY_AUTO_DATA_SWITCH = 3
        private const val CAPABILITY_TYPE_VOICE = 1
        private const val CAPABILITY_TYPE_VIDEO = 2
        private const val CAPTIVE_HTTP_KEY = "captive_portal_http_url"
        private const val CAPTIVE_HTTPS_KEY = "captive_portal_https_url"
        private val CARRIERS_URI: Uri = Telephony.Carriers.CONTENT_URI

        private val REG_TECH_LTE: Int by lazy { readRegTechConst("REGISTRATION_TECH_LTE", 0) }
        private val REG_TECH_IWLAN: Int by lazy { readRegTechConst("REGISTRATION_TECH_IWLAN", 1) }
        private val REG_TECH_CROSS_SIM: Int by lazy { readRegTechConst("REGISTRATION_TECH_CROSS_SIM", 2) }
        private val REG_TECH_NR: Int by lazy { readRegTechConst("REGISTRATION_TECH_NR", 3) }

        private fun readRegTechConst(fieldName: String, fallback: Int): Int {
            return try {
                Class.forName("android.telephony.ims.stub.ImsRegistrationImplBase")
                    .getField(fieldName)
                    .getInt(null)
            } catch (_: Exception) {
                fallback
            }
        }
    }

    private data class SimTarget(
        val slotIndex: Int,
        val subId: Int,
        val carrierName: String,
        val isEmbedded: Boolean,
        val mccMnc: String
    )

    private data class ApnHealth(
        val apnImsPresent: Boolean = true,
        val conflictDetail: String = ""
    )

    private fun findMethod(obj: Any, name: String, paramCount: Int? = null): Method? {
        var clazz: Class<*>? = obj.javaClass
        while (clazz != null) {
            try {
                val method = clazz.declaredMethods.firstOrNull {
                    it.name == name && (paramCount == null || it.parameterTypes.size == paramCount)
                }
                if (method != null) {
                    method.isAccessible = true
                    return method
                }
            } catch (_: Exception) {
            }
            clazz = clazz.superclass
        }
        for (iface in obj.javaClass.interfaces) {
            try {
                val method = iface.declaredMethods.firstOrNull {
                    it.name == name && (paramCount == null || it.parameterTypes.size == paramCount)
                }
                if (method != null) {
                    method.isAccessible = true
                    return method
                }
            } catch (_: Exception) {
            }
        }
        return null
    }

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        Log.d(TAG, "BrokerInstrumentation starting...")

        val queryOnlyArg = arguments?.getString("query_only") == "true" || arguments?.getBoolean("query_only") == true
        val clearArg = arguments?.getString("clear") == "true" || arguments?.getBoolean("clear") == true
        val action = when {
            arguments?.containsKey("action") == true -> arguments.getString("action") ?: "apply"
            queryOnlyArg -> "query"
            clearArg -> "clear"
            else -> "apply"
        }
        val targetSlot = arguments?.getString("target_slot")?.toIntOrNull()
            ?: arguments?.getInt("target_slot", -1)
            ?: -1

        Log.d(TAG, "action=$action, targetSlot=$targetSlot")

        Thread {
            var errorMsg: String? = null
            try {
                HiddenApiBypass.addHiddenApiExemptions("L")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to apply HiddenApiBypass exemptions", e)
            }

            try {
                var uiAutomation: UiAutomation? = null
                var retries = 5
                while (retries > 0) {
                    try {
                        uiAutomation = getUiAutomation()
                        if (uiAutomation != null) break
                    } catch (e: Exception) {
                        retries--
                        if (retries > 0) {
                            try { Thread.sleep(400) } catch (_: Exception) {}
                        } else {
                            throw e
                        }
                    }
                }

                uiAutomation?.adoptShellPermissionIdentity()
                try {
                    when (action) {
                        "query" -> queryAllSlots()
                        "fix_apn" -> fixApnConflicts(targetSlot)
                        "reset_ims" -> resetImsSlots(targetSlot)
                        "persistent_volte_enable" -> setPersistentVolte(targetSlot, enable = true)
                        "persistent_volte_restore" -> setPersistentVolte(targetSlot, enable = false)
                        "captive_portal_write" -> writeCaptivePortalSettings(
                            httpUrl = arguments?.getString("http_url"),
                            httpsUrl = arguments?.getString("https_url")
                        )
                        "captive_portal_reset" -> writeCaptivePortalSettings(httpUrl = null, httpsUrl = null)
                        "clear" -> clearAndPoll(targetSlot)
                        else -> applyAndPoll(targetSlot)
                    }
                    if (action == "apply" || action == "clear") {
                        showImsStatusNotification(action == "apply")
                    }
                } finally {
                    try {
                        uiAutomation?.dropShellPermissionIdentity()
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to drop shell permission identity", e)
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to run instrumentation", t)
                errorMsg = t.message ?: t.javaClass.simpleName
            } finally {
                val resBundle = Bundle()
                val code = if (errorMsg == null) 0 else {
                    resBundle.putString("error", errorMsg)
                    1
                }
                finish(code, resBundle)
            }
        }.start()
    }

    override fun finish(resultCode: Int, results: Bundle?) {
        try {
            super.finish(resultCode, results)
        } catch (e: Exception) {
            Log.e(TAG, "Exception during Instrumentation.finish() ignored safely", e)
        }
    }

    private fun resolveSimTargets(subManager: SubscriptionManager): List<SimTarget> {
        val targets = mutableListOf<SimTarget>()

        fun toSimTarget(slot: Int, info: SubscriptionInfo): SimTarget {
            val rawName = info.displayName?.toString()?.takeIf { it.isNotBlank() }
                ?: info.carrierName?.toString()?.takeIf { it.isNotBlank() }
                ?: "SIM ${slot + 1}"
            val mcc = info.mccString ?: ""
            val mnc = info.mncString ?: ""
            return SimTarget(
                slotIndex = slot,
                subId = info.subscriptionId,
                carrierName = rawName,
                isEmbedded = info.isEmbedded,
                mccMnc = if (mcc.isNotEmpty()) "$mcc$mnc" else ""
            )
        }

        for (slot in 0..1) {
            try {
                val info = subManager.getActiveSubscriptionInfoForSimSlotIndex(slot)
                if (info != null && info.subscriptionId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                    targets.add(toSimTarget(slot, info))
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to get active sub info for slot $slot", e)
            }
        }

        try {
            val list = subManager.activeSubscriptionInfoList ?: emptyList()
            for (info in list) {
                val subId = info.subscriptionId
                if (subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) continue
                var slotIndex = info.simSlotIndex
                if (slotIndex < 0) {
                    val existingSlots = targets.map { it.slotIndex }.toSet()
                    slotIndex = if (!existingSlots.contains(1)) 1 else if (!existingSlots.contains(0)) 0 else 1
                }
                if (targets.none { it.slotIndex == slotIndex || it.subId == subId }) {
                    targets.add(toSimTarget(slotIndex, info))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to inspect activeSubscriptionInfoList", e)
        }

        Log.d(TAG, "Resolved SIM targets: $targets")
        return targets.sortedBy { it.slotIndex }
    }

    private fun isConfigApplied(slot: Int): Boolean {
        return try {
            File(context.filesDir, "config_applied_$slot.txt").readText().trim().toBoolean()
        } catch (_: Exception) {
            false
        }
    }

    private fun setConfigApplied(slot: Int, applied: Boolean) {
        try {
            File(context.filesDir, "config_applied_$slot.txt").writeText(applied.toString())
        } catch (_: Exception) {
        }
    }

    private fun inspectApnHealth(mccMnc: String): ApnHealth {
        if (mccMnc.isBlank()) return ApnHealth()
        return try {
            var hasImsApn = false
            val conflicts = mutableListOf<String>()
            val projection = arrayOf("_id", "apn", "type", "carrier_enabled")
            context.contentResolver.query(
                CARRIERS_URI,
                projection,
                "numeric=?",
                arrayOf(mccMnc),
                null
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndex("_id")
                val apnIdx = cursor.getColumnIndex("apn")
                val typeIdx = cursor.getColumnIndex("type")
                val enabledIdx = cursor.getColumnIndex("carrier_enabled")
                while (cursor.moveToNext()) {
                    val id = if (idIdx >= 0) cursor.getInt(idIdx) else -1
                    val apn = if (apnIdx >= 0) (cursor.getString(apnIdx) ?: "") else ""
                    val typeRaw = if (typeIdx >= 0) (cursor.getString(typeIdx) ?: "") else ""
                    val enabled = if (enabledIdx >= 0) cursor.getInt(enabledIdx) else 1
                    if (enabled == 0) continue

                    val types = typeRaw.split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }
                    if (apn.equals("ims", ignoreCase = true) && types.contains("ims")) {
                        hasImsApn = true
                    } else if (types.contains("ims") && types.any { it != "ims" && it != "ia" }) {
                        conflicts.add("_id=$id|$apn|$typeRaw")
                    }
                }
            }
            ApnHealth(
                apnImsPresent = hasImsApn,
                conflictDetail = conflicts.joinToString("; ")
            )
        } catch (e: Exception) {
            Log.w(TAG, "APN health inspection skipped for $mccMnc: ${e.message}")
            ApnHealth()
        }
    }

    private fun fixApnConflicts(targetSlot: Int) {
        val subManager = context.getSystemService(SubscriptionManager::class.java) ?: return
        val telephonyManager = context.getSystemService(TelephonyManager::class.java) ?: return
        val targets = resolveSimTargets(subManager)

        for (target in targets) {
            if (targetSlot != -1 && target.slotIndex != targetSlot) continue
            if (target.mccMnc.isBlank()) continue

            try {
                val projection = arrayOf("_id", "apn", "type")
                context.contentResolver.query(
                    CARRIERS_URI,
                    projection,
                    "numeric=?",
                    arrayOf(target.mccMnc),
                    null
                )?.use { cursor ->
                    val idIdx = cursor.getColumnIndex("_id")
                    val apnIdx = cursor.getColumnIndex("apn")
                    val typeIdx = cursor.getColumnIndex("type")
                    while (cursor.moveToNext()) {
                        val id = cursor.getInt(idIdx)
                        val apn = cursor.getString(apnIdx) ?: ""
                        val typeRaw = cursor.getString(typeIdx) ?: ""
                        val types = typeRaw.split(",").map { it.trim() }.filter { it.isNotEmpty() }

                        if (apn.equals("ims", ignoreCase = true) && types.any { it.equals("ims", ignoreCase = true) }) {
                            val cv = ContentValues().apply { put("sub_id", target.subId) }
                            context.contentResolver.update(CARRIERS_URI, cv, "_id=?", arrayOf(id.toString()))
                            Log.d(TAG, "Bound ims APN _id=$id to subId=${target.subId}")
                        } else if (types.any { it.equals("ims", ignoreCase = true) } && types.size > 1) {
                            val cleaned = types.filterNot { it.equals("ims", ignoreCase = true) }.joinToString(",")
                            val cv = ContentValues().apply { put("type", cleaned) }
                            context.contentResolver.update(CARRIERS_URI, cv, "_id=?", arrayOf(id.toString()))
                            Log.d(TAG, "Cleaned ims type from APN _id=$id ($apn): $typeRaw -> $cleaned")
                        }
                    }
                }
                resetImsForSub(telephonyManager, target.subId, target.slotIndex)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fix APN conflict for slot=${target.slotIndex} (${target.mccMnc})", e)
            }
        }
        pollStatuses(if (targetSlot >= 0) setOf(targetSlot) else emptySet(), isClear = false)
    }

    private fun resetImsSlots(targetSlot: Int) {
        val subManager = context.getSystemService(SubscriptionManager::class.java) ?: return
        val telephonyManager = context.getSystemService(TelephonyManager::class.java) ?: return
        val targets = resolveSimTargets(subManager)
        val touchedSlots = mutableSetOf<Int>()
        for (target in targets) {
            if (targetSlot != -1 && target.slotIndex != targetSlot) continue
            resetImsForSub(telephonyManager, target.subId, target.slotIndex)
            touchedSlots.add(target.slotIndex)
        }
        pollStatuses(touchedSlots, isClear = false)
    }

    private fun setPersistentVolte(targetSlot: Int, enable: Boolean) {
        val subManager = context.getSystemService(SubscriptionManager::class.java) ?: return
        val telephonyManager = context.getSystemService(TelephonyManager::class.java) ?: return
        val targets = resolveSimTargets(subManager)
        for (target in targets) {
            if (targetSlot != -1 && target.slotIndex != targetSlot) continue
            val res = if (enable) {
                PersistentVolteHelper.enable(context, target.subId)
            } else {
                PersistentVolteHelper.restore(context, target.subId)
            }
            if (res.isFailure) {
                throw IllegalStateException(
                    "Persistent VoLTE ${if (enable) "enable" else "restore"} failed for slot ${target.slotIndex}: ${res.exceptionOrNull()?.message}",
                    res.exceptionOrNull()
                )
            }
            resetImsForSub(telephonyManager, target.subId, target.slotIndex)
        }
        queryAllSlots()
    }

    private fun validateCaptivePortalUrl(raw: String, scheme: String): String {
        val trimmed = raw.trim()
        require(trimmed.isNotEmpty()) { "Captive portal $scheme URL cannot be empty" }
        val uri = URI(trimmed)
        require(uri.isAbsolute && uri.scheme.equals(scheme, ignoreCase = true) && !uri.host.isNullOrBlank()) {
            "Invalid $scheme URL: $trimmed"
        }
        return trimmed
    }

    private fun writeCaptivePortalSettings(httpUrl: String?, httpsUrl: String?) {
        val resolver = context.contentResolver
        val originalHttp = Settings.Global.getString(resolver, CAPTIVE_HTTP_KEY)
        val originalHttps = Settings.Global.getString(resolver, CAPTIVE_HTTPS_KEY)
        val targetHttp = httpUrl?.let { validateCaptivePortalUrl(it, "http") }
        val targetHttps = httpsUrl?.let { validateCaptivePortalUrl(it, "https") }

        fun putOrDelete(key: String, value: String?) {
            if (value == null) {
                resolver.call(Settings.Global.CONTENT_URI, "DELETE_global", key, null)
            } else {
                check(Settings.Global.putString(resolver, key, value)) { "Failed writing $key" }
            }
        }

        try {
            putOrDelete(CAPTIVE_HTTP_KEY, targetHttp)
            putOrDelete(CAPTIVE_HTTPS_KEY, targetHttps)
            check(Settings.Global.getString(resolver, CAPTIVE_HTTP_KEY) == targetHttp) {
                "Captive portal HTTP verification mismatch"
            }
            check(Settings.Global.getString(resolver, CAPTIVE_HTTPS_KEY) == targetHttps) {
                "Captive portal HTTPS verification mismatch"
            }
            saveCaptivePortalSnapshot()
        } catch (t: Throwable) {
            try {
                putOrDelete(CAPTIVE_HTTP_KEY, originalHttp)
                putOrDelete(CAPTIVE_HTTPS_KEY, originalHttps)
                saveCaptivePortalSnapshot()
            } catch (rollback: Throwable) {
                t.addSuppressed(rollback)
            }
            throw t
        }
    }

    private fun saveCaptivePortalSnapshot() {
        val resolver = context.contentResolver
        val http = Settings.Global.getString(resolver, CAPTIVE_HTTP_KEY) ?: ""
        val https = Settings.Global.getString(resolver, CAPTIVE_HTTPS_KEY) ?: ""
        SimConfigStore.saveCaptivePortalState(
            context,
            CaptivePortalState(
                httpUrl = http,
                httpsUrl = https,
                lastUpdatedEpochMs = System.currentTimeMillis()
            )
        )
    }

    private fun queryAllSlots(): List<SimLiveStatus> {
        val subManager = context.getSystemService(SubscriptionManager::class.java) ?: return emptyList()
        val imsManager = context.getSystemService(ImsManager::class.java)
        val carrierConfigManager = context.getSystemService(CarrierConfigManager::class.java)
        val telephonyManager = context.getSystemService(TelephonyManager::class.java)
        val targets = resolveSimTargets(subManager)
        val sharedPrefs = context.getSharedPreferences(SimConfigStore.PREFS_NAME, Context.MODE_PRIVATE)
        val statuses = mutableListOf<SimLiveStatus>()

        saveCaptivePortalSnapshot()

        for (target in targets) {
            val status = querySingleSlotStatus(target, imsManager, carrierConfigManager, telephonyManager, sharedPrefs)
            sharedPrefs.edit().putBoolean("ims_registered_slot_${target.slotIndex}", status.imsRegistered).commit()
            SimConfigStore.saveLiveStatus(context, status)
            statuses.add(status)
            Log.d(TAG, "Status slot=${target.slotIndex} subId=${target.subId} reg=${status.imsRegistered} transport=${status.imsTransport} crossSim=${status.crossSimEnabled} wfc=${status.wfcEnabled}")
        }
        return statuses
    }

    @Suppress("DEPRECATION")
    private fun querySingleSlotStatus(
        target: SimTarget,
        imsManager: ImsManager?,
        carrierConfigManager: CarrierConfigManager?,
        telephonyManager: TelephonyManager?,
        sharedPrefs: android.content.SharedPreferences
    ): SimLiveStatus {
        var imsRegistered = false
        var imsTransport = "NONE"
        var crossSimEnabled = false
        var wfcEnabled = false
        var capVolte = false
        var capVowifi = false
        var capVonr = false
        var capVt = false
        var capNrNsa = false
        var capNrSa = false

        val mmTel = try {
            imsManager?.getImsMmTelManager(target.subId)
        } catch (e: Exception) {
            Log.w(TAG, "getImsMmTelManager failed for subId=${target.subId}", e)
            null
        }

        if (mmTel != null) {
            try {
                crossSimEnabled = mmTel.isCrossSimCallingEnabled
            } catch (e: Exception) {
                Log.w(TAG, "isCrossSimCallingEnabled failed for subId=${target.subId}", e)
            }
            try {
                wfcEnabled = mmTel.isVoWiFiSettingEnabled
            } catch (e: Exception) {
                Log.w(TAG, "isVoWiFiSettingEnabled failed for subId=${target.subId}", e)
            }

            try {
                val isAvail = findMethod(mmTel, "isAvailable", 2)
                if (isAvail != null) {
                    capVolte = (isAvail.invoke(mmTel, CAPABILITY_TYPE_VOICE, REG_TECH_LTE) as? Boolean) ?: false
                    capVowifi = (isAvail.invoke(mmTel, CAPABILITY_TYPE_VOICE, REG_TECH_IWLAN) as? Boolean) ?: false
                    capVonr = (isAvail.invoke(mmTel, CAPABILITY_TYPE_VOICE, REG_TECH_NR) as? Boolean) ?: false
                    capVt = (isAvail.invoke(mmTel, CAPABILITY_TYPE_VIDEO, REG_TECH_LTE) as? Boolean) ?: false
                }
            } catch (e: Exception) {
                Log.w(TAG, "MmTel isAvailable failed for subId=${target.subId}", e)
            }

            val latch = CountDownLatch(1)
            val regObserved = AtomicBoolean(false)
            val transportRef = AtomicReference("NONE")
            val callback = object : RegistrationManager.RegistrationCallback() {
                override fun onRegistered(attributes: ImsRegistrationAttributes) {
                    regObserved.set(true)
                    transportRef.set(resolveTransportLabel(attributes))
                    latch.countDown()
                }

                @Suppress("OVERRIDE_DEPRECATION")
                override fun onRegistered(imsTransportType: Int) {
                    regObserved.set(true)
                    transportRef.set(
                        if (imsTransportType == AccessNetworkConstants.TRANSPORT_TYPE_WLAN) "IWLAN" else "WWAN"
                    )
                    latch.countDown()
                }

                override fun onUnregistered(info: ImsReasonInfo) {
                    regObserved.set(false)
                    transportRef.set("NONE")
                    latch.countDown()
                }
            }

            try {
                mmTel.registerImsRegistrationCallback({ runnable -> runnable.run() }, callback)
                latch.await(250, TimeUnit.MILLISECONDS)
                imsRegistered = regObserved.get()
                imsTransport = transportRef.get()
            } catch (e: Exception) {
                Log.w(TAG, "registerImsRegistrationCallback failed for subId=${target.subId}", e)
            } finally {
                try {
                    mmTel.unregisterImsRegistrationCallback(callback)
                } catch (_: Exception) {
                }
            }
        }

        if (!imsRegistered) {
            val fallbackReg = checkImsRegisteredFallback(target.subId)
            if (fallbackReg) {
                imsRegistered = true
                if (imsTransport == "NONE") imsTransport = "REGISTERED"
            }
        }

        try {
            val subTm = telephonyManager?.createForSubscriptionId(target.subId)
            val ss = subTm?.serviceState
            val nrRegInfo = ss?.networkRegistrationInfoList?.firstOrNull {
                it.transportType == AccessNetworkConstants.TRANSPORT_TYPE_WWAN &&
                    (it.domain and NetworkRegistrationInfo.DOMAIN_PS) != 0
            }
            if (nrRegInfo != null) {
                val nrState = try {
                    findMethod(nrRegInfo, "getNrState", 0)?.invoke(nrRegInfo) as? Int ?: NetworkRegistrationInfo.NR_STATE_NONE
                } catch (_: Exception) {
                    NetworkRegistrationInfo.NR_STATE_NONE
                }
                capNrNsa = nrState == NetworkRegistrationInfo.NR_STATE_CONNECTED ||
                    nrState == NetworkRegistrationInfo.NR_STATE_NOT_RESTRICTED
                capNrSa = nrRegInfo.accessNetworkTechnology == TelephonyManager.NETWORK_TYPE_NR
            }
        } catch (e: Exception) {
            Log.w(TAG, "NR ServiceState check failed for subId=${target.subId}", e)
        }

        val carrierConfigVersion = try {
            carrierConfigManager?.getConfigForSubId(target.subId)
                ?.getString("carrier_config_version_string")
                ?.lineSequence()
                ?.firstOrNull()
                ?.trim()
                ?: ""
        } catch (_: Exception) {
            ""
        }

        val apnHealth = inspectApnHealth(target.mccMnc)
        val persistentVolte = PersistentVolteHelper.query(context, target.subId)
        val configApplied = isConfigApplied(target.slotIndex)
        val overrideCount = if (configApplied) {
            SimConfigStore.buildCarrierConfigBundle(SimConfigStore.loadSimConfig(sharedPrefs, target.slotIndex)).keySet().size
        } else {
            0
        }

        return SimLiveStatus(
            slotIndex = target.slotIndex,
            subId = target.subId,
            carrierName = target.carrierName,
            isEmbedded = target.isEmbedded,
            mccMnc = target.mccMnc,
            imsRegistered = imsRegistered,
            imsTransport = imsTransport,
            crossSimEnabled = crossSimEnabled,
            wfcEnabled = wfcEnabled,
            configApplied = configApplied,
            carrierConfigVersion = carrierConfigVersion,
            overrideCount = overrideCount,
            apnConflictDetail = apnHealth.conflictDetail,
            apnImsPresent = apnHealth.apnImsPresent,
            capVolte = capVolte,
            capVowifi = capVowifi,
            capVonr = capVonr,
            capVt = capVt,
            capNrNsa = capNrNsa,
            capNrSa = capNrSa,
            persistentVolteSupported = persistentVolte.supported,
            persistentVolteOptIn = persistentVolte.optIn,
            persistentVolteUserEnabled = persistentVolte.userEnabled,
            persistentVolteCanRestore = persistentVolte.canRestore,
            lastUpdatedEpochMs = System.currentTimeMillis()
        )
    }

    private fun resolveTransportLabel(attributes: ImsRegistrationAttributes): String {
        val attrFlags = try {
            attributes.attributeFlags
        } catch (_: Exception) {
            0
        }
        if ((attrFlags and ImsRegistrationAttributes.ATTR_EPDG_OVER_CELL_INTERNET) != 0) {
            return "CROSS_SIM"
        }
        val tech = try {
            val method = findMethod(attributes, "getRegistrationTechnology")
            method?.invoke(attributes) as? Int
        } catch (_: Exception) {
            null
        }
        return when (tech) {
            REG_TECH_CROSS_SIM -> "CROSS_SIM"
            REG_TECH_IWLAN -> "IWLAN"
            REG_TECH_NR -> "NR"
            REG_TECH_LTE -> "LTE"
            else -> when (attributes.transportType) {
                AccessNetworkConstants.TRANSPORT_TYPE_WLAN -> "IWLAN"
                AccessNetworkConstants.TRANSPORT_TYPE_WWAN -> "LTE/NR"
                else -> "REGISTERED"
            }
        }
    }

    private fun checkImsRegisteredFallback(subId: Int): Boolean {
        return try {
            val serviceManagerClass = Class.forName("android.os.ServiceManager")
            val getServiceMethod = serviceManagerClass.getMethod("getService", String::class.java)
            val binder = getServiceMethod.invoke(null, Context.TELEPHONY_SERVICE) as android.os.IBinder
            val stubClass = Class.forName("com.android.internal.telephony.ITelephony\$Stub")
            val asInterfaceMethod = stubClass.getMethod("asInterface", android.os.IBinder::class.java)
            val telephonyService = asInterfaceMethod.invoke(null, binder)
            val iTelephonyClass = Class.forName("com.android.internal.telephony.ITelephony")

            for (name in listOf("isImsRegisteredForSubscriber", "isImsRegistered")) {
                try {
                    val method = iTelephonyClass.declaredMethods.firstOrNull {
                        it.name == name && it.parameterTypes.size == 1 && it.parameterTypes[0] == Int::class.javaPrimitiveType
                    }
                    if (method != null) {
                        method.isAccessible = true
                        return method.invoke(telephonyService, subId) as Boolean
                    }
                } catch (_: Exception) {
                }
            }
            false
        } catch (_: Exception) {
            false
        }
    }

    private fun applyOverrideConfig(carrierConfigManager: CarrierConfigManager, subId: Int, bundle: android.os.PersistableBundle?) {
        val overrideMethod = carrierConfigManager.javaClass.declaredMethods.firstOrNull {
            it.name == "overrideConfig" && it.parameterTypes.size == 3
        } ?: carrierConfigManager.javaClass.declaredMethods.firstOrNull {
            it.name == "overrideConfig"
        }
        if (overrideMethod != null) {
            overrideMethod.isAccessible = true
            if (overrideMethod.parameterTypes.size == 3) {
                overrideMethod.invoke(carrierConfigManager, subId, bundle, false)
            } else {
                overrideMethod.invoke(carrierConfigManager, subId, bundle)
            }
        } else {
            Log.e(TAG, "CarrierConfigManager.overrideConfig method not found")
        }
    }

    private fun applyMmTelSettings(
        imsManager: ImsManager?,
        telephonyManager: TelephonyManager,
        subId: Int,
        config: SimOverrideConfig
    ) {
        val mmTel = try {
            imsManager?.getImsMmTelManager(subId)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to obtain ImsMmTelManager for subId=$subId", e)
            null
        }
        if (mmTel != null) {
            invokeHiddenSetter(mmTel, "setCrossSimCallingEnabled", Boolean::class.javaPrimitiveType!!, config.crossSim, subId)
            if (config.vowifi) {
                invokeHiddenSetter(mmTel, "setVoWiFiSettingEnabled", Boolean::class.javaPrimitiveType!!, true, subId)
            }
            if (config.wfcRoaming) {
                invokeHiddenSetter(mmTel, "setVoWiFiRoamingSettingEnabled", Boolean::class.javaPrimitiveType!!, true, subId)
            }
            if (config.wfcMode != WfcMode.DEFAULT) {
                invokeHiddenSetter(mmTel, "setVoWiFiModeSetting", Int::class.javaPrimitiveType!!, config.wfcMode, subId)
            }
            if (config.wfcRoamingMode != WfcMode.DEFAULT) {
                invokeHiddenSetter(mmTel, "setVoWiFiRoamingModeSetting", Int::class.javaPrimitiveType!!, config.wfcRoamingMode, subId)
            }
        }

        if (config.autoDataSwitch) {
            try {
                val subTm = telephonyManager.createForSubscriptionId(subId)
                val policyMethod = findMethod(subTm, "setMobileDataPolicyEnabled", 2)
                if (policyMethod != null) {
                    policyMethod.invoke(subTm, MOBILE_DATA_POLICY_AUTO_DATA_SWITCH, true)
                    Log.d(TAG, "Enabled MOBILE_DATA_POLICY_AUTO_DATA_SWITCH for subId=$subId")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to enable auto data switch policy for subId=$subId", e)
            }
        }
    }

    private fun invokeHiddenSetter(target: Any, methodName: String, argType: Class<*>, value: Any, subId: Int) {
        try {
            val method = target.javaClass.declaredMethods.firstOrNull {
                it.name == methodName && it.parameterTypes.size == 1 && it.parameterTypes[0] == argType
            } ?: findMethod(target, methodName, 1)
            if (method != null) {
                method.isAccessible = true
                method.invoke(target, value)
                Log.d(TAG, "Invoked $methodName($value) for subId=$subId")
            } else {
                Log.w(TAG, "Method $methodName not found on ${target.javaClass.simpleName}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed invoking $methodName($value) for subId=$subId", e)
        }
    }

    private fun resetImsForSub(telephonyManager: TelephonyManager, subId: Int, slotIndex: Int) {
        try {
            val subTelephonyManager = telephonyManager.createForSubscriptionId(subId)
            val resetImsMethod = findMethod(subTelephonyManager, "resetIms")
            if (resetImsMethod != null) {
                if (resetImsMethod.parameterTypes.isNotEmpty()) {
                    resetImsMethod.invoke(subTelephonyManager, slotIndex)
                } else {
                    resetImsMethod.invoke(subTelephonyManager)
                }
                Log.d(TAG, "IMS reset sent for slot=$slotIndex subId=$subId")
                return
            }
        } catch (e: Exception) {
            Log.w(TAG, "TelephonyManager.resetIms failed for subId=$subId, trying ITelephony fallback", e)
        }
        try {
            val serviceManagerClass = Class.forName("android.os.ServiceManager")
            val binder = serviceManagerClass.getMethod("getService", String::class.java)
                .invoke(null, Context.TELEPHONY_SERVICE) as android.os.IBinder
            val stubClass = Class.forName("com.android.internal.telephony.ITelephony\$Stub")
            val telephonyService = stubClass.getMethod("asInterface", android.os.IBinder::class.java)
                .invoke(null, binder)
            val resetMethod = Class.forName("com.android.internal.telephony.ITelephony").declaredMethods.firstOrNull {
                it.name == "resetIms" && it.parameterTypes.size == 1 && it.parameterTypes[0] == Int::class.javaPrimitiveType
            }
            if (resetMethod != null) {
                resetMethod.isAccessible = true
                resetMethod.invoke(telephonyService, slotIndex)
                Log.d(TAG, "ITelephony.resetIms sent for slot=$slotIndex")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to reset IMS via fallback for slot=$slotIndex subId=$subId", e)
        }
    }

    private fun applyAndPoll(targetSlot: Int) {
        val sharedPrefs = context.getSharedPreferences(SimConfigStore.PREFS_NAME, Context.MODE_PRIVATE)
        val subManager = context.getSystemService(SubscriptionManager::class.java) ?: return
        val carrierConfigManager = context.getSystemService(CarrierConfigManager::class.java) ?: return
        val telephonyManager = context.getSystemService(TelephonyManager::class.java) ?: return
        val imsManager = context.getSystemService(ImsManager::class.java)

        val simTargets = resolveSimTargets(subManager)
        val touchedSlots = mutableSetOf<Int>()

        for (target in simTargets) {
            if (targetSlot != -1 && target.slotIndex != targetSlot) {
                Log.d(TAG, "Skipping slot ${target.slotIndex} (targetSlot=$targetSlot)")
                continue
            }

            val config = SimConfigStore.loadSimConfig(sharedPrefs, target.slotIndex)
            if (targetSlot == -1 && !config.overrideEnabled) {
                Log.d(TAG, "Skipping slot ${target.slotIndex} because overrideEnabled=false")
                continue
            }

            if (targetSlot != -1 && !config.overrideEnabled) {
                sharedPrefs.edit().putBoolean("override_enabled_slot_${target.slotIndex}", true).commit()
            }

            val bundle = SimConfigStore.buildCarrierConfigBundle(config)
            Log.d(TAG, "Applying config to slot ${target.slotIndex} (subId=${target.subId}, keys=${bundle.keySet().size}, crossSim=${config.crossSim})")

            try {
                // Clear prior overrides first so disabled toggles never leave stale keys merged
                applyOverrideConfig(carrierConfigManager, target.subId, null)
                applyOverrideConfig(carrierConfigManager, target.subId, bundle)
                setConfigApplied(target.slotIndex, true)
                touchedSlots.add(target.slotIndex)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to apply CarrierConfig for subId=${target.subId}", e)
            }

            applyMmTelSettings(imsManager, telephonyManager, target.subId, config)
            resetImsForSub(telephonyManager, target.subId, target.slotIndex)
        }

        if (touchedSlots.isNotEmpty()) {
            SimConfigStore.setNeedsReapplyAfterBoot(sharedPrefs, false, commit = true)
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)?.cancel(204)
        }
        pollStatuses(touchedSlots, isClear = false)
    }

    private fun clearAndPoll(targetSlot: Int) {
        val sharedPrefs = context.getSharedPreferences(SimConfigStore.PREFS_NAME, Context.MODE_PRIVATE)
        val subManager = context.getSystemService(SubscriptionManager::class.java) ?: return
        val carrierConfigManager = context.getSystemService(CarrierConfigManager::class.java) ?: return
        val telephonyManager = context.getSystemService(TelephonyManager::class.java) ?: return
        val imsManager = context.getSystemService(ImsManager::class.java)

        val simTargets = resolveSimTargets(subManager)
        val touchedSlots = mutableSetOf<Int>()

        for (target in simTargets) {
            if (targetSlot != -1 && target.slotIndex != targetSlot) {
                Log.d(TAG, "Skipping clear for slot ${target.slotIndex} (targetSlot=$targetSlot)")
                continue
            }

            Log.d(TAG, "Clearing overrides for slot ${target.slotIndex} (subId=${target.subId})")
            try {
                applyOverrideConfig(carrierConfigManager, target.subId, null)
                setConfigApplied(target.slotIndex, false)
                sharedPrefs.edit().putBoolean("override_enabled_slot_${target.slotIndex}", false).commit()
                touchedSlots.add(target.slotIndex)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to clear CarrierConfig for subId=${target.subId}", e)
            }

            try {
                val mmTel = imsManager?.getImsMmTelManager(target.subId)
                if (mmTel != null) {
                    invokeHiddenSetter(mmTel, "setCrossSimCallingEnabled", Boolean::class.javaPrimitiveType!!, false, target.subId)
                }
            } catch (_: Exception) {
            }

            resetImsForSub(telephonyManager, target.subId, target.slotIndex)
        }

        pollStatuses(touchedSlots, isClear = true)
    }

    private fun pollStatuses(touchedSlots: Set<Int>, isClear: Boolean) {
        val maxPolls = if (touchedSlots.isEmpty()) 1 else if (isClear) 6 else 16
        for (elapsed in 0 until maxPolls) {
            val statuses = queryAllSlots()
            val relevant = if (touchedSlots.isEmpty()) statuses else statuses.filter { it.slotIndex in touchedSlots }
            if (elapsed >= 4) {
                if (!isClear && relevant.isNotEmpty() && relevant.all { it.imsRegistered }) {
                    Log.d(TAG, "All targeted SIMs registered after ${elapsed}s")
                    break
                }
                if (isClear) {
                    break
                }
            }
            if (elapsed < maxPolls - 1) {
                try { Thread.sleep(1000) } catch (_: Exception) {}
            }
        }
    }

    private fun showImsStatusNotification(isActivate: Boolean) {
        val channelId = "ims_status_channel"
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(channelId, context.getString(R.string.notification_channel_ims_status), NotificationManager.IMPORTANCE_HIGH)
            notificationManager.createNotificationChannel(ch)
        }

        val s0 = SimConfigStore.loadLiveStatus(context, 0)
        val s1 = SimConfigStore.loadLiveStatus(context, 1)
        val anyRegistered = s0.imsRegistered || s1.imsRegistered
        val title = if (isActivate) {
            if (anyRegistered) context.getString(R.string.notification_title_volte_success) else context.getString(R.string.notification_title_volte_done)
        } else {
            context.getString(R.string.notification_title_config_restored)
        }
        val body = if (isActivate) {
            listOf(s0, s1)
                .filter { it.subId != -1 || it.imsRegistered || it.configApplied }
                .joinToString(" | ") { status ->
                    val name = status.carrierName.ifBlank { "SIM ${status.slotIndex + 1}" }
                    val state = if (status.imsRegistered) "IMS (${status.imsTransport})" else "Not Reg"
                    val backup = if (status.crossSimEnabled) "+Backup" else ""
                    "$name: $state$backup"
                }.ifBlank { context.getString(R.string.notification_config_cleared) }
        } else {
            context.getString(R.string.notification_config_cleared)
        }

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, channelId)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }

        val notification = builder
            .setSmallIcon(R.drawable.ic_tile_ims)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(203, notification)
    }
}
