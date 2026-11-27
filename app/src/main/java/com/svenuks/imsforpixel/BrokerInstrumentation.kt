package com.svenuks.imsforpixel

import android.app.Instrumentation
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.UiAutomation
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.telephony.AccessNetworkConstants
import android.telephony.CarrierConfigManager
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.telephony.ims.ImsManager
import android.telephony.ims.ImsMmTelManager
import android.telephony.ims.ImsReasonInfo
import android.telephony.ims.ImsRegistrationAttributes
import android.telephony.ims.RegistrationManager
import android.util.Log
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.File
import java.lang.reflect.Method
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class BrokerInstrumentation : Instrumentation() {
    companion object {
        private const val TAG = "VoLTEBrokerInst"
        private const val MOBILE_DATA_POLICY_AUTO_DATA_SWITCH = 3
        private const val REGISTRATION_TECH_LTE = 0
        private const val REGISTRATION_TECH_IWLAN = 1
        private const val REGISTRATION_TECH_CROSS_SIM = 2
        private const val REGISTRATION_TECH_NR = 3
    }

    private data class SimTarget(
        val slotIndex: Int,
        val subId: Int,
        val carrierName: String,
        val isEmbedded: Boolean,
        val mccMnc: String
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
                        "clear" -> clearAndPoll(targetSlot)
                        else -> applyAndPoll(targetSlot)
                    }
                    if (action != "query") {
                        showImsStatusNotification(action != "clear")
                    }
                } finally {
                    try {
                        uiAutomation?.dropShellPermissionIdentity()
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to drop shell permission identity", e)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to run instrumentation", e)
            } finally {
                finish(0, Bundle())
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

    private fun queryAllSlots(): List<SimLiveStatus> {
        val subManager = context.getSystemService(SubscriptionManager::class.java) ?: return emptyList()
        val imsManager = context.getSystemService(ImsManager::class.java)
        val targets = resolveSimTargets(subManager)
        val sharedPrefs = context.getSharedPreferences(SimConfigStore.PREFS_NAME, Context.MODE_PRIVATE)
        val statuses = mutableListOf<SimLiveStatus>()

        for (target in targets) {
            val status = querySingleSlotStatus(target, imsManager)
            sharedPrefs.edit().putBoolean("ims_registered_slot_${target.slotIndex}", status.imsRegistered).commit()
            SimConfigStore.saveLiveStatus(context, status)
            statuses.add(status)
            Log.d(TAG, "Status slot=${target.slotIndex} subId=${target.subId} reg=${status.imsRegistered} transport=${status.imsTransport} crossSim=${status.crossSimEnabled} wfc=${status.wfcEnabled}")
        }
        return statuses
    }

    private fun querySingleSlotStatus(target: SimTarget, imsManager: ImsManager?): SimLiveStatus {
        var imsRegistered = false
        var imsTransport = "NONE"
        var crossSimEnabled = false
        var wfcEnabled = false

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
            val fallbackReg = checkImsRegisteredFallback(target.subId, target.slotIndex)
            if (fallbackReg) {
                imsRegistered = true
                if (imsTransport == "NONE") imsTransport = "REGISTERED"
            }
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
            configApplied = isConfigApplied(target.slotIndex)
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
            REGISTRATION_TECH_CROSS_SIM -> "CROSS_SIM"
            REGISTRATION_TECH_IWLAN -> "IWLAN"
            REGISTRATION_TECH_NR -> "NR"
            REGISTRATION_TECH_LTE -> "LTE"
            else -> when (attributes.transportType) {
                AccessNetworkConstants.TRANSPORT_TYPE_WLAN -> "IWLAN"
                AccessNetworkConstants.TRANSPORT_TYPE_WWAN -> "LTE/NR"
                else -> "REGISTERED"
            }
        }
    }

    private fun checkImsRegisteredFallback(subId: Int, slotIndex: Int): Boolean {
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
                        return method.invoke(telephonyService, if (name == "isImsRegisteredForSubscriber") subId else subId) as Boolean
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
                } else {
                    Log.w(TAG, "setMobileDataPolicyEnabled not found on TelephonyManager")
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
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to reset IMS for subId=$subId", e)
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

            // Ensure overrideEnabled is marked true if user explicitly targeted this slot
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
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(203, notification)
    }
}
