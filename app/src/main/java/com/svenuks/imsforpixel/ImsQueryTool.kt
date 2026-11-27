package com.svenuks.imsforpixel

import android.os.IBinder
import android.telephony.SubscriptionInfo

object ImsQueryTool {
    @JvmStatic
    fun main(args: Array<String>) {
        try {
            val serviceManagerClass = Class.forName("android.os.ServiceManager")
            val getServiceMethod = serviceManagerClass.getMethod("getService", String::class.java)

            val isubBinder = getServiceMethod.invoke(null, "isub") as? IBinder ?: return
            val isubStubClass = Class.forName("com.android.internal.telephony.ISub\$Stub")
            val isubService = isubStubClass.getMethod("asInterface", IBinder::class.java).invoke(null, isubBinder) ?: return
            val iSubClass = Class.forName("com.android.internal.telephony.ISub")

            val phoneBinder = getServiceMethod.invoke(null, "phone") as? IBinder ?: return
            val stubClass = Class.forName("com.android.internal.telephony.ITelephony\$Stub")
            val telephonyService = stubClass.getMethod("asInterface", IBinder::class.java).invoke(null, phoneBinder) ?: return
            val iTelephonyClass = Class.forName("com.android.internal.telephony.ITelephony")

            val activeInfos = fetchActiveSubscriptionInfos(isubService, iSubClass)

            for (slot in 0..1) {
                val info = activeInfos.firstOrNull { it.simSlotIndex == slot }
                    ?: getSubInfoForSlot(isubService, iSubClass, slot)
                val subId = info?.subscriptionId ?: resolveSubIdFallback(isubService, iSubClass, slot)
                val carrierName = (info?.displayName?.toString()?.takeIf { it.isNotBlank() }
                    ?: info?.carrierName?.toString()?.takeIf { it.isNotBlank() }
                    ?: "")
                    .replace(":", " ")
                    .replace("\n", " ")
                    .trim()
                val isEmbedded = info?.isEmbedded ?: false
                val mccMnc = if (info != null && !info.mccString.isNullOrEmpty()) {
                    "${info.mccString}${info.mncString ?: ""}"
                } else {
                    ""
                }

                val isImsRegistered = checkImsRegistered(telephonyService, iTelephonyClass, subId, slot)
                val crossSimEnabled = checkBooleanSubSetting(
                    telephonyService,
                    iTelephonyClass,
                    listOf("isCrossSimCallingEnabled", "isCrossSimCallingEnabledByUser"),
                    subId
                ) ?: false
                val wfcState = checkBooleanSubSetting(
                    telephonyService,
                    iTelephonyClass,
                    listOf("isWifiCallingEnabled", "isWfcEnabled"),
                    subId
                )?.toString() ?: "UNKNOWN"
                val transport = if (isImsRegistered) {
                    resolveTransportFallback(telephonyService, iTelephonyClass, subId)
                } else {
                    "NONE"
                }

                println("STATUS:$slot:$subId:$carrierName:$isEmbedded:$mccMnc:$isImsRegistered:$transport:$crossSimEnabled:$wfcState")
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun fetchActiveSubscriptionInfos(isubService: Any, iSubClass: Class<*>): List<SubscriptionInfo> {
        val candidates = listOf("getActiveSubscriptionInfoList", "getActiveSubscriptionInfoListWithInfo")
        for (name in candidates) {
            for (method in iSubClass.declaredMethods.filter { it.name == name }) {
                try {
                    method.isAccessible = true
                    val res = when (method.parameterTypes.size) {
                        0 -> method.invoke(isubService)
                        2 -> method.invoke(isubService, "com.android.shell", null)
                        3 -> method.invoke(isubService, "com.android.shell", null, false)
                        else -> null
                    }
                    if (res is List<*>) {
                        return res.filterIsInstance<SubscriptionInfo>()
                    }
                } catch (_: Exception) {
                }
            }
        }
        return emptyList()
    }

    private fun getSubInfoForSlot(isubService: Any, iSubClass: Class<*>, slot: Int): SubscriptionInfo? {
        for (method in iSubClass.declaredMethods.filter { it.name == "getActiveSubscriptionInfoForSimSlotIndex" }) {
            try {
                method.isAccessible = true
                val res = when (method.parameterTypes.size) {
                    1 -> method.invoke(isubService, slot)
                    3 -> method.invoke(isubService, slot, "com.android.shell", null)
                    else -> null
                }
                if (res is SubscriptionInfo) return res
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun resolveSubIdFallback(isubService: Any, iSubClass: Class<*>, slot: Int): Int {
        try {
            val getSubIdMethod = iSubClass.declaredMethods.firstOrNull {
                it.name == "getSubId" && it.parameterTypes.size == 1 && it.parameterTypes[0] == Int::class.javaPrimitiveType
            }
            if (getSubIdMethod != null) {
                getSubIdMethod.isAccessible = true
                val res = getSubIdMethod.invoke(isubService, slot)
                return when (res) {
                    is IntArray -> if (res.isNotEmpty()) res[0] else -1
                    is Int -> res
                    is Array<*> -> if (res.isNotEmpty()) (res[0] as? Int ?: -1) else -1
                    else -> -1
                }
            }
        } catch (_: Exception) {
        }
        return -1
    }

    private fun checkBooleanSubSetting(
        telephonyService: Any,
        iTelephonyClass: Class<*>,
        methodNames: List<String>,
        subId: Int
    ): Boolean? {
        if (subId <= 0) return null
        for (name in methodNames) {
            try {
                val method = iTelephonyClass.declaredMethods.firstOrNull {
                    it.name == name && it.parameterTypes.size == 1 && it.parameterTypes[0] == Int::class.javaPrimitiveType
                }
                if (method != null) {
                    method.isAccessible = true
                    val res = method.invoke(telephonyService, subId) as? Boolean
                    if (res != null) return res
                }
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun resolveTransportFallback(
        telephonyService: Any,
        iTelephonyClass: Class<*>,
        subId: Int
    ): String {
        if (subId <= 0) return "REGISTERED"
        try {
            val wfcActive = checkBooleanSubSetting(
                telephonyService,
                iTelephonyClass,
                listOf("isWifiCallingAvailable", "isImsOverWifiRegisteredForSubscriber"),
                subId
            ) ?: false
            if (wfcActive) return "IWLAN"
        } catch (_: Exception) {
        }
        return "REGISTERED"
    }

    private fun checkImsRegistered(telephonyService: Any, iTelephonyClass: Class<*>, subId: Int, slot: Int): Boolean {
        if (subId == -1 || subId == Int.MAX_VALUE) return false

        for (name in listOf("isImsRegisteredForSubscriber", "isImsRegistered")) {
            try {
                val method = iTelephonyClass.declaredMethods.firstOrNull {
                    it.name == name && it.parameterTypes.size == 1 && it.parameterTypes[0] == Int::class.javaPrimitiveType
                }
                if (method != null) {
                    method.isAccessible = true
                    val res = method.invoke(telephonyService, subId) as? Boolean
                    if (res != null) return res
                }
            } catch (_: Exception) {
            }
        }

        try {
            val method = iTelephonyClass.declaredMethods.firstOrNull { it.name == "isImsRegistered" }
            if (method != null) {
                method.isAccessible = true
                val res = method.invoke(telephonyService, slot) as? Boolean
                if (res != null) return res
            }
        } catch (_: Exception) {
        }

        return false
    }
}
