package com.svenuks.imsforpixel

import android.annotation.SuppressLint
import android.content.Context
import android.telephony.SubscriptionManager
import android.util.AtomicFile
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest

/**
 * Experimental rootless Persistent VoLTE helper using ProvisioningManager.KEY_VOIMS_OPT_IN_STATUS
 * and SubscriptionManager subscription properties with atomic per-ICCID rollback backups.
 * Must be invoked under adopted shell permission identity (e.g., from BrokerInstrumentation).
 */
object PersistentVolteHelper {
    private const val TAG = "PersistentVolteHelper"

    data class PersistentVolteStatus(
        val supported: Boolean = false,
        val optIn: Boolean = false,
        val userEnabled: Boolean = false,
        val canRestore: Boolean = false
    )

    private data class OriginalValues(val optIn: Int, val userSetting: Int) {
        init {
            require(optIn in -1..1 && userSetting in -1..1) { "Unexpected subscription values: $optIn/$userSetting" }
        }
    }

    private data class BackupEntry(val identity: String, val values: OriginalValues)

    private class ReflectedAccessor(private val context: Context, private val subId: Int) {
        private val intType = Int::class.javaPrimitiveType!!
        private val boolType = Boolean::class.javaPrimitiveType!!
        private val provisioningClass = Class.forName("android.telephony.ims.ProvisioningManager")
        private val mmTelClass = Class.forName("android.telephony.ims.ImsMmTelManager")
        private val provisioning = provisioningClass.getMethod("createForSubscriptionId", intType).invoke(null, subId)
        private val mmTel = mmTelClass.getMethod("createForSubscriptionId", intType).invoke(null, subId)
        private val getProvisioning = provisioningClass.getMethod("getProvisioningIntValue", intType)
        private val setProvisioning = provisioningClass.getMethod("setProvisioningIntValue", intType, intType)
        private val getUser = mmTelClass.getMethod("isAdvancedCallingSettingEnabled")
        private val setUser = mmTelClass.getMethod("setAdvancedCallingSettingEnabled", boolType)
        private val getProperty = SubscriptionManager::class.java.getMethod(
            "getIntegerSubscriptionProperty", intType, String::class.java, intType, Context::class.java
        )
        private val setProperty = SubscriptionManager::class.java.getMethod(
            "setSubscriptionProperty", intType, String::class.java, String::class.java
        )
        private val optInKey = provisioningClass.getField("KEY_VOIMS_OPT_IN_STATUS").getInt(null)
        private val optInColumn = SubscriptionManager::class.java.getField("VOIMS_OPT_IN_STATUS").get(null) as String
        private val userColumn = SubscriptionManager::class.java.getField("ENHANCED_4G_MODE_ENABLED").get(null) as String

        @SuppressLint("MissingPermission")
        fun simIdentityDigest(): String {
            val sm = context.getSystemService(SubscriptionManager::class.java)
            val info = sm?.getActiveSubscriptionInfo(subId) ?: error("Subscription $subId is not active")
            val rawId = info.iccId?.takeIf { it.isNotBlank() } ?: "sub:$subId:${info.mccString}:${info.mncString}"
            return MessageDigest.getInstance("SHA-256")
                .digest(rawId.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }

        fun readOriginal(): OriginalValues = OriginalValues(readProperty(optInColumn), readProperty(userColumn))

        fun readOptIn(): Boolean {
            val value = getProvisioning.invoke(provisioning, optInKey) as Int
            check(value == 0 || value == 1) { "Provisioning read failed: $value" }
            return value == 1
        }

        fun readUserEnabled(): Boolean = getUser.invoke(mmTel) as Boolean

        fun enableAndVerify() {
            writeOptIn(1)
            setUser.invoke(mmTel, true)
            check(readOriginal() == OriginalValues(1, 1) && readOptIn() && readUserEnabled()) {
                "Persistent VoLTE verification failed"
            }
        }

        fun restoreAndVerify(values: OriginalValues) {
            var failure: Throwable? = null
            fun attempt(block: () -> Unit) {
                try {
                    block()
                } catch (t: Throwable) {
                    val prev = failure
                    if (prev == null) failure = t else prev.addSuppressed(t)
                }
            }
            attempt { writeProperty(userColumn, values.userSetting) }
            attempt { writeOptIn(if (values.optIn == 1) 1 else 0) }
            if (values.optIn == -1) {
                attempt { writeProperty(optInColumn, -1) }
            }
            attempt {
                check(readOriginal() == values && readOptIn() == (values.optIn == 1)) {
                    "Original VoLTE settings verification failed"
                }
            }
            failure?.let { throw it }
        }

        private fun writeOptIn(value: Int) {
            val res = setProvisioning.invoke(provisioning, optInKey, value) as Int
            check(res == 0) { "Provisioning write failed: $res" }
        }

        private fun readProperty(column: String): Int {
            val v = getProperty.invoke(null, subId, column, Int.MIN_VALUE, context) as Int
            check(v in -1..1) { "Subscription property read failed ($column=$v)" }
            return v
        }

        private fun writeProperty(column: String, value: Int) {
            setProperty.invoke(null, subId, column, value.toString())
            check(readProperty(column) == value) { "Subscription property write failed ($column=$value)" }
        }
    }

    private fun backupFile(context: Context, subId: Int): AtomicFile =
        AtomicFile(File(context.noBackupFilesDir, "persistent_volte_$subId.json"))

    private fun readBackup(context: Context, subId: Int): BackupEntry? {
        val file = backupFile(context, subId)
        val text = try {
            file.openRead().bufferedReader().use { it.readText() }
        } catch (_: FileNotFoundException) {
            return null
        }
        val json = JSONObject(text)
        if (json.optInt("version", 1) != 1) return null
        return BackupEntry(
            identity = json.getString("identity"),
            values = OriginalValues(json.getInt("optIn"), json.getInt("user"))
        )
    }

    private fun saveBackup(context: Context, subId: Int, entry: BackupEntry) {
        val file = backupFile(context, subId)
        val bytes = JSONObject()
            .put("version", 1)
            .put("identity", entry.identity)
            .put("optIn", entry.values.optIn)
            .put("user", entry.values.userSetting)
            .toString()
            .toByteArray(Charsets.UTF_8)
        val stream = file.startWrite()
        try {
            stream.write(bytes)
            file.finishWrite(stream)
        } catch (t: Throwable) {
            file.failWrite(stream)
            throw t
        }
    }

    private fun clearBackup(context: Context, subId: Int) {
        backupFile(context, subId).delete()
    }

    fun query(context: Context, subId: Int): PersistentVolteStatus {
        if (subId <= 0) return PersistentVolteStatus()
        return try {
            val accessor = ReflectedAccessor(context, subId)
            val optIn = accessor.readOptIn()
            val userEnabled = accessor.readUserEnabled()
            val canRestore = readBackup(context, subId) != null
            PersistentVolteStatus(
                supported = true,
                optIn = optIn,
                userEnabled = userEnabled,
                canRestore = canRestore
            )
        } catch (t: Throwable) {
            Log.d(TAG, "Persistent VoLTE query unavailable for subId=$subId: ${t.message}")
            PersistentVolteStatus(
                supported = false,
                canRestore = runCatching { readBackup(context, subId) != null }.getOrDefault(false)
            )
        }
    }

    fun enable(context: Context, subId: Int): Result<Unit> {
        return try {
            val accessor = ReflectedAccessor(context, subId)
            val identity = accessor.simIdentityDigest()
            val existing = readBackup(context, subId)
            check(existing == null || existing.identity == identity) {
                "SIM identity does not match saved VoLTE backup"
            }
            val before = accessor.readOriginal()
            if (existing == null) {
                saveBackup(context, subId, BackupEntry(identity, before))
            }
            try {
                accessor.enableAndVerify()
                Log.i(TAG, "Enabled Persistent VoLTE for subId=$subId")
            } catch (t: Throwable) {
                try {
                    if (accessor.simIdentityDigest() == identity) {
                        accessor.restoreAndVerify(before)
                    }
                } catch (rollback: Throwable) {
                    t.addSuppressed(rollback)
                }
                throw t
            }
            Result.success(Unit)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to enable Persistent VoLTE for subId=$subId", t)
            Result.failure(t)
        }
    }

    fun restore(context: Context, subId: Int): Result<Unit> {
        return try {
            val existing = readBackup(context, subId)
                ?: return Result.failure(IllegalStateException("No original VoLTE backup saved for subId=$subId"))
            val accessor = ReflectedAccessor(context, subId)
            check(accessor.simIdentityDigest() == existing.identity) {
                "SIM identity mismatch; aborting restore"
            }
            accessor.restoreAndVerify(existing.values)
            clearBackup(context, subId)
            Log.i(TAG, "Restored original Persistent VoLTE state for subId=$subId")
            Result.success(Unit)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to restore Persistent VoLTE for subId=$subId", t)
            Result.failure(t)
        }
    }
}
