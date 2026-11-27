package com.svenuks.imsforpixel

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import com.flyfishxu.kadb.Kadb
import com.flyfishxu.kadb.cert.KadbCert
import com.flyfishxu.kadb.cert.KadbCertPolicy
import com.flyfishxu.kadb.cert.OkioFilePrivateKeyStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.Path.Companion.toPath
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : ComponentActivity() {

    companion object {
        @JvmStatic
        var pairingPort: Int? = null
        var onAuthStatusChanged: (() -> Unit)? = null
        var onInstrumentDone: ((action: String, success: Boolean) -> Unit)? = null
    }

    private val actionPair: String
        get() = "$packageName.ACTION_PAIR"

    fun showImsStatusNotification(isActivate: Boolean) {
        val channelId = "ims_status_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(channelId, getString(R.string.notification_channel_ims_status), NotificationManager.IMPORTANCE_HIGH)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
        }
        val s0 = SimConfigStore.loadLiveStatus(this, 0)
        val s1 = SimConfigStore.loadLiveStatus(this, 1)
        val anyRegistered = s0.imsRegistered || s1.imsRegistered
        val title = if (isActivate) {
            if (anyRegistered) getString(R.string.notification_title_volte_success) else getString(R.string.notification_title_volte_done)
        } else {
            getString(R.string.notification_title_config_restored)
        }
        val body = if (isActivate) {
            listOf(s0, s1)
                .filter { it.subId != -1 || it.imsRegistered || it.configApplied }
                .joinToString(" | ") { status ->
                    val name = status.carrierName.ifBlank { "SIM ${status.slotIndex + 1}" }
                    val state = if (status.imsRegistered) "IMS (${status.imsTransport})" else "Not Reg"
                    val backup = if (status.crossSimEnabled) "+Backup" else ""
                    "$name: $state$backup"
                }.ifBlank { getString(R.string.notification_config_cleared) }
        } else {
            getString(R.string.notification_config_cleared)
        }
        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java)?.notify(203, notification)
    }

    private var pairingReceiver: BroadcastReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            HiddenApiBypass.addHiddenApiExemptions("L")
        } catch (e: Exception) {
            Log.e("ImsForPixelPlus", "Failed to apply HiddenApiBypass exemptions", e)
        }

        try {
            val privateKeyFile = File(filesDir, "kadb_private_key.pem")
            val store = OkioFilePrivateKeyStore(privateKeyFile.absolutePath.toPath())
            KadbCert.configure(
                store = store,
                policy = KadbCertPolicy(),
                additionalPrivateKeysPem = emptyList()
            )
            KadbCert.ensureReady()
        } catch (e: Exception) {
            Log.e("ImsForPixelPlus", "Failed to configure KadbCert", e)
        }

        val prefs = getSharedPreferences(SimConfigStore.PREFS_NAME, Context.MODE_PRIVATE)
        SimConfigStore.ensureDefaults(prefs)

        val filter = IntentFilter(actionPair)
        pairingReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == actionPair) {
                    val remoteInput = RemoteInput.getResultsFromIntent(intent)
                    if (remoteInput != null) {
                        val code = remoteInput.getCharSequence("extra_pairing_code")?.toString()?.trim()
                        if (!code.isNullOrEmpty()) {
                            handleNotificationPairing(code)
                        }
                    }
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(pairingReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(pairingReceiver, filter)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 102)
            }
        }

        setContent {
            val recheckSignal = remember { mutableStateOf(System.currentTimeMillis()) }
            MainScreen(recheckSignal = recheckSignal)
        }
    }

    fun requestNotificationPermissionAndShow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            } else {
                showPairingNotification()
            }
        } else {
            showPairingNotification()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                showPairingNotification()
            } else {
                Toast.makeText(this, getString(R.string.notification_permission_required), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "pairing_channel",
                getString(R.string.notification_channel_pairing),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(R.string.notification_channel_pairing_desc)
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun showPairingNotification() {
        createNotificationChannel()

        val replyLabel = getString(R.string.notification_pairing_reply_label)
        val remoteInput = RemoteInput.Builder("extra_pairing_code")
            .setLabel(replyLabel)
            .build()

        val intent = Intent(actionPair).apply {
            `package` = packageName
        }

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        val replyPendingIntent = PendingIntent.getBroadcast(this, 0, intent, flags)

        val action = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_send,
            getString(R.string.notification_pairing_send_action),
            replyPendingIntent
        )
            .addRemoteInput(remoteInput)
            .build()

        val notification = NotificationCompat.Builder(this, "pairing_channel")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(getString(R.string.notification_channel_pairing))
            .setContentText(getString(R.string.notification_pairing_prompt))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .setAutoCancel(false)
            .addAction(action)
            .build()

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(202, notification)
    }

    private fun showPairingStatusNotification(text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = NotificationCompat.Builder(this, "pairing_channel")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(getString(R.string.notification_channel_pairing))
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        manager.notify(202, notification)
    }

    private fun handleNotificationPairing(code: String) {
        val port = pairingPort
        if (port == null) {
            Toast.makeText(this, getString(R.string.toast_pairing_port_missing), Toast.LENGTH_LONG).show()
            showPairingStatusNotification(getString(R.string.notification_pairing_failed_port))
            return
        }

        Toast.makeText(this, getString(R.string.toast_pairing_in_progress), Toast.LENGTH_SHORT).show()
        showPairingStatusNotification(getString(R.string.notification_pairing_port_progress, port))

        CoroutineScope(Dispatchers.IO).launch {
            val result = try {
                Kadb.pair("127.0.0.1", port, code, filesDir.absolutePath)
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }

            withContext(Dispatchers.Main) {
                result.fold(
                    onSuccess = {
                        Toast.makeText(this@MainActivity, getString(R.string.toast_pairing_success), Toast.LENGTH_LONG).show()
                        showPairingStatusNotification(getString(R.string.notification_pairing_success))
                        onAuthStatusChanged?.invoke()
                    },
                    onFailure = { error ->
                        Toast.makeText(this@MainActivity, getString(R.string.toast_pairing_failed, error.message ?: ""), Toast.LENGTH_LONG).show()
                        showPairingStatusNotification(getString(R.string.notification_pairing_failed, error.message ?: ""))
                    }
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        pairingReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (_: Exception) {
            }
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(202)
    }
}

// Color Palette
val PrimaryGradEnd = Color(0xFF2A5298)
val BackgroundDark = Color(0xFF0F172A)
val CardBackground = Color(0xFF1E293B)
val SurfaceSubtle = Color(0xFF162032)
val BorderColor = Color(0xFF334155)
val AccentGreen = Color(0xFF10B981)
val AccentBlue = Color(0xFF38BDF8)
val AccentOrange = Color(0xFFF59E0B)
val AccentRed = Color(0xFFEF4444)
val TextLight = Color(0xFFF8FAFC)
val TextMuted = Color(0xFF94A3B8)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(recheckSignal: MutableState<Long> = remember { mutableStateOf(System.currentTimeMillis()) }) {
    var selectedSimSlot by remember { mutableStateOf(0) }
    var portInput by remember { mutableStateOf("") }
    var isApplying by remember { mutableStateOf(false) }
    val isInstrumenting = remember { AtomicBoolean(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var status0 by remember { mutableStateOf(SimConfigStore.loadLiveStatus(context, 0)) }
    var status1 by remember { mutableStateOf(SimConfigStore.loadLiveStatus(context, 1)) }

    LaunchedEffect(Unit) {
        MainActivity.onAuthStatusChanged = {
            recheckSignal.value = System.currentTimeMillis()
        }
        MainActivity.onInstrumentDone = { action, _ ->
            if (action == "apply" || action == "clear") {
                (context as? MainActivity)?.showImsStatusNotification(action == "apply")
            }
            recheckSignal.value = System.currentTimeMillis()
        }
        while (true) {
            delay(1000)
            recheckSignal.value = System.currentTimeMillis()
        }
    }

    LaunchedEffect(recheckSignal.value) {
        status0 = SimConfigStore.loadLiveStatus(context, 0)
        status1 = SimConfigStore.loadLiveStatus(context, 1)
    }

    LaunchedEffect(portInput) {
        val port = portInput.toIntOrNull()
        if (port != null && port > 0 && port <= 65535) {
            withContext(Dispatchers.IO) {
                var activeKadb: Kadb? = null
                try {
                    while (true) {
                        if (isInstrumenting.get()) {
                            delay(1000)
                            continue
                        }
                        try {
                            val kadb = activeKadb ?: Kadb.create("127.0.0.1", port, 5000, 5000).also { activeKadb = it }
                            val pathRes = kadb.shell("pm path ${context.packageName}")
                            if (pathRes.exitCode == 0) {
                                val path = pathRes.output.trim().substringAfter("package:")
                                if (path.isNotEmpty()) {
                                    val queryCmd = "export CLASSPATH=$path; app_process /system/bin com.svenuks.imsforpixel.ImsQueryTool"
                                    val queryRes = kadb.shell(queryCmd)
                                    if (queryRes.exitCode == 0) {
                                        for (line in queryRes.output.lines()) {
                                            if (line.startsWith("STATUS:")) {
                                                val parts = line.split(":")
                                                if (parts.size >= 9) {
                                                    val slot = parts[1].toIntOrNull() ?: continue
                                                    val subId = parts[2].toIntOrNull() ?: -1
                                                    val carrierName = parts[3]
                                                    val isEmbedded = parts[4].toBoolean()
                                                    val mccMnc = parts[5]
                                                    val imsRegistered = parts[6].toBoolean()
                                                    val parsedTransport = parts[7]
                                                    val crossSim = parts[8].toBoolean()
                                                    val wfcRaw = parts.getOrNull(9)

                                                    val existing = SimConfigStore.loadLiveStatus(context, slot)
                                                    val wfc = when (wfcRaw) {
                                                        "true" -> true
                                                        "false" -> false
                                                        else -> existing.wfcEnabled
                                                    }
                                                    val transport = when {
                                                        !imsRegistered -> "NONE"
                                                        parsedTransport == "REGISTERED" && existing.imsRegistered &&
                                                            existing.imsTransport in listOf("CROSS_SIM", "IWLAN", "NR", "LTE") -> existing.imsTransport
                                                        else -> parsedTransport
                                                    }
                                                    val updated = existing.copy(
                                                        subId = if (subId > 0) subId else existing.subId,
                                                        carrierName = carrierName.ifBlank { existing.carrierName },
                                                        isEmbedded = isEmbedded,
                                                        mccMnc = mccMnc.ifBlank { existing.mccMnc },
                                                        imsRegistered = imsRegistered,
                                                        imsTransport = transport,
                                                        crossSimEnabled = crossSim,
                                                        wfcEnabled = wfc
                                                    )
                                                    SimConfigStore.saveLiveStatus(context, updated)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.d("LocalAdb", "Error in background IMS check: ${e.message}")
                            try { activeKadb?.close() } catch (_: Exception) {}
                            activeKadb = null
                        }
                        delay(3000)
                    }
                } finally {
                    try { activeKadb?.close() } catch (_: Exception) {}
                }
            }
        }
    }

    fun runInstrumentation(action: String, targetSlot: Int) {
        val port = portInput.toIntOrNull()
        if (port == null || port <= 0 || port > 65535) {
            Toast.makeText(context, context.getString(R.string.toast_enable_wireless_debugging), Toast.LENGTH_SHORT).show()
            return
        }

        val prefs = context.getSharedPreferences(SimConfigStore.PREFS_NAME, Context.MODE_PRIVATE)
        if (action == "apply" && targetSlot == -1) {
            val s0Enabled = SimConfigStore.loadSimConfig(prefs, 0).overrideEnabled
            val s1Enabled = SimConfigStore.loadSimConfig(prefs, 1).overrideEnabled
            if (!s0Enabled && !s1Enabled) {
                Toast.makeText(context, context.getString(R.string.toast_no_sim_override_enabled), Toast.LENGTH_LONG).show()
                return
            }
        }

        isApplying = true
        isInstrumenting.set(true)
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    Kadb.create("127.0.0.1", port, 60000, 60000).use { kadb ->
                        val cmd = "am instrument -w -e action $action -e target_slot $targetSlot ${context.packageName}/com.svenuks.imsforpixel.BrokerInstrumentation"
                        val response = kadb.shell(cmd)
                        if (response.exitCode == 0) {
                            Result.success(response.output)
                        } else {
                            Result.failure(Exception("Exit code ${response.exitCode}: ${response.output}"))
                        }
                    }
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }
            isApplying = false
            isInstrumenting.set(false)
            result.fold(
                onSuccess = {
                    MainActivity.onInstrumentDone?.invoke(action, true)
                },
                onFailure = { error ->
                    val resId = if (action == "clear") R.string.toast_restore_failed else R.string.toast_activation_failed
                    Toast.makeText(context, context.getString(resId, error.message ?: ""), Toast.LENGTH_LONG).show()
                }
            )
        }
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = PrimaryGradEnd,
            background = BackgroundDark,
            surface = CardBackground,
            onPrimary = TextLight,
            onBackground = TextLight,
            onSurface = TextLight
        )
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            stringResource(R.string.app_name),
                            fontWeight = FontWeight.Bold,
                            color = TextLight
                        )
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = CardBackground)
                )
            },
            containerColor = BackgroundDark
        ) { paddingValues ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    Text(
                        text = stringResource(R.string.app_subtitle),
                        color = TextMuted,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }

                item {
                    SimSelectorTabs(
                        selectedSlot = selectedSimSlot,
                        status0 = status0,
                        status1 = status1,
                        onSlotSelected = { selectedSimSlot = it }
                    )
                }

                item {
                    val activeStatus = if (selectedSimSlot == 0) status0 else status1
                    ConfigPanel(
                        slotIndex = selectedSimSlot,
                        liveStatus = activeStatus,
                        isApplying = isApplying,
                        recheckSignal = recheckSignal,
                        onRefreshStatus = { runInstrumentation("query", -1) },
                        onApplySlot = { runInstrumentation("apply", selectedSimSlot) },
                        onRestoreSlot = { runInstrumentation("clear", selectedSimSlot) }
                    )
                }

                item {
                    LocalAdbCard(
                        recheckSignal = recheckSignal,
                        status0 = status0,
                        status1 = status1,
                        portInput = portInput,
                        onPortInputChange = { portInput = it },
                        isApplying = isApplying,
                        onApplyClick = { runInstrumentation("apply", -1) },
                        onRestoreClick = { runInstrumentation("clear", -1) }
                    )
                }
            }
        }
    }
}

private fun formatSimTabLabel(slot: Int, status: SimLiveStatus, fallback: String): String {
    if (status.carrierName.isBlank()) return fallback
    val simType = if (status.isEmbedded) "eSIM" else "pSIM"
    return "SIM ${slot + 1} · ${status.carrierName} ($simType)"
}

@Composable
fun SimSelectorTabs(
    selectedSlot: Int,
    status0: SimLiveStatus,
    status1: SimLiveStatus,
    onSlotSelected: (Int) -> Unit
) {
    val label0 = formatSimTabLabel(0, status0, stringResource(R.string.sim_slot_1))
    val label1 = formatSimTabLabel(1, status1, stringResource(R.string.sim_slot_2))

    TabRow(
        selectedTabIndex = selectedSlot,
        containerColor = CardBackground,
        contentColor = TextLight,
        indicator = { tabPositions ->
            TabRowDefaults.SecondaryIndicator(
                Modifier.tabIndicatorOffset(tabPositions[selectedSlot]),
                color = PrimaryGradEnd
            )
        },
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, BorderColor, RoundedCornerShape(12.dp))
    ) {
        Tab(
            selected = selectedSlot == 0,
            onClick = { onSlotSelected(0) },
            text = {
                Text(
                    label0,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        )
        Tab(
            selected = selectedSlot == 1,
            onClick = { onSlotSelected(1) },
            text = {
                Text(
                    label1,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        )
    }
}

@Composable
fun ConfigPanel(
    slotIndex: Int,
    liveStatus: SimLiveStatus,
    isApplying: Boolean,
    recheckSignal: MutableState<Long>,
    onRefreshStatus: () -> Unit,
    onApplySlot: () -> Unit,
    onRestoreSlot: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember(slotIndex) {
        context.getSharedPreferences(SimConfigStore.PREFS_NAME, Context.MODE_PRIVATE)
    }

    var config by remember(slotIndex) {
        mutableStateOf(SimConfigStore.loadSimConfig(prefs, slotIndex))
    }
    var showAdvanced by remember(slotIndex) { mutableStateOf(false) }

    // Sync config if instrumentation updated overrideEnabled externally
    LaunchedEffect(slotIndex, recheckSignal.value) {
        val latestOverride = prefs.getBoolean("override_enabled_slot_$slotIndex", config.overrideEnabled)
        if (latestOverride != config.overrideEnabled) {
            config = config.copy(overrideEnabled = latestOverride)
        }
    }

    fun updateConfig(newConfig: SimOverrideConfig) {
        config = newConfig
        SimConfigStore.saveSimConfig(prefs, slotIndex, newConfig, commit = true)
    }

    val panelTitle = if (liveStatus.carrierName.isNotBlank()) {
        val simType = if (liveStatus.isEmbedded) "eSIM" else "pSIM"
        "${liveStatus.carrierName} ($simType · SIM ${slotIndex + 1})"
    } else {
        stringResource(R.string.config_panel_title, slotIndex + 1)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, BorderColor, RoundedCornerShape(16.dp)),
        colors = CardDefaults.cardColors(containerColor = CardBackground)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = panelTitle,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = TextLight,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                IconButton(
                    onClick = onRefreshStatus,
                    enabled = !isApplying,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = stringResource(R.string.btn_refresh_status),
                        tint = TextMuted,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            // Live Diagnostics Chips
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                StatusChip(
                    text = if (liveStatus.configApplied) stringResource(R.string.badge_config_applied) else stringResource(R.string.badge_config_default),
                    bgColor = if (liveStatus.configApplied) AccentGreen.copy(alpha = 0.15f) else BorderColor.copy(alpha = 0.3f),
                    textColor = if (liveStatus.configApplied) AccentGreen else TextMuted
                )
                val imsLabel = if (liveStatus.imsRegistered) {
                    "IMS: ${liveStatus.imsTransport}"
                } else {
                    stringResource(R.string.badge_ims_not_registered)
                }
                StatusChip(
                    text = imsLabel,
                    bgColor = if (liveStatus.imsRegistered) AccentGreen.copy(alpha = 0.15f) else AccentRed.copy(alpha = 0.15f),
                    textColor = if (liveStatus.imsRegistered) AccentGreen else AccentRed
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                StatusChip(
                    text = if (liveStatus.crossSimEnabled) stringResource(R.string.diag_backup_on) else stringResource(R.string.diag_backup_off),
                    bgColor = if (liveStatus.crossSimEnabled) AccentBlue.copy(alpha = 0.15f) else BorderColor.copy(alpha = 0.3f),
                    textColor = if (liveStatus.crossSimEnabled) AccentBlue else TextMuted
                )
                StatusChip(
                    text = if (liveStatus.wfcEnabled) stringResource(R.string.diag_wfc_on) else stringResource(R.string.diag_wfc_off),
                    bgColor = if (liveStatus.wfcEnabled) AccentGreen.copy(alpha = 0.15f) else BorderColor.copy(alpha = 0.3f),
                    textColor = if (liveStatus.wfcEnabled) AccentGreen else TextMuted
                )
            }

            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(color = BorderColor)
            Spacer(modifier = Modifier.height(6.dp))

            // Master Per-SIM Override Toggle
            ToggleRow(
                title = stringResource(R.string.toggle_override_sim_title),
                description = stringResource(R.string.toggle_override_sim_desc),
                checked = config.overrideEnabled
            ) { enabled ->
                updateConfig(config.copy(overrideEnabled = enabled))
            }

            if (!config.overrideEnabled) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(SurfaceSubtle)
                        .border(1.dp, BorderColor, RoundedCornerShape(10.dp))
                        .padding(10.dp)
                ) {
                    Text(
                        text = stringResource(R.string.notice_sim_untouched),
                        color = TextMuted,
                        fontSize = 11.sp
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            Column(modifier = Modifier.alpha(if (config.overrideEnabled) 1f else 0.45f)) {
                ToggleRow(stringResource(R.string.toggle_volte_title), stringResource(R.string.toggle_volte_desc), config.volte) {
                    updateConfig(config.copy(volte = it, overrideEnabled = true))
                }
                ToggleRow(stringResource(R.string.toggle_vonr_title), stringResource(R.string.toggle_vonr_desc), config.vonr) {
                    updateConfig(config.copy(vonr = it, overrideEnabled = true))
                }
                ToggleRow(stringResource(R.string.toggle_vowifi_title), stringResource(R.string.toggle_vowifi_desc), config.vowifi) {
                    updateConfig(config.copy(vowifi = it, overrideEnabled = true))
                }
                ToggleRow(stringResource(R.string.toggle_wfc_roaming_title), stringResource(R.string.toggle_wfc_roaming_desc), config.wfcRoaming) {
                    updateConfig(config.copy(wfcRoaming = it, overrideEnabled = true))
                }
                ToggleRow(stringResource(R.string.toggle_cross_sim_title), stringResource(R.string.toggle_cross_sim_desc), config.crossSim) {
                    updateConfig(config.copy(crossSim = it, overrideEnabled = true))
                }

                Spacer(modifier = Modifier.height(6.dp))
                WfcModeSelector(
                    label = stringResource(R.string.label_wfc_home_mode),
                    selectedMode = config.wfcMode,
                    onModeSelected = { updateConfig(config.copy(wfcMode = it, overrideEnabled = true)) }
                )
                Spacer(modifier = Modifier.height(8.dp))
                WfcModeSelector(
                    label = stringResource(R.string.label_wfc_roaming_mode),
                    selectedMode = config.wfcRoamingMode,
                    onModeSelected = { updateConfig(config.copy(wfcRoamingMode = it, overrideEnabled = true)) }
                )
                Spacer(modifier = Modifier.height(4.dp))

                ToggleRow(stringResource(R.string.toggle_ss_ut_title), stringResource(R.string.toggle_ss_ut_desc), config.ssUt) {
                    updateConfig(config.copy(ssUt = it, overrideEnabled = true))
                }

                // Expandable Advanced Settings
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { showAdvanced = !showAdvanced }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = if (showAdvanced) stringResource(R.string.section_advanced_ims_hide) else stringResource(R.string.section_advanced_ims_show),
                        color = AccentBlue,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp
                    )
                    Icon(
                        imageVector = if (showAdvanced) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                        tint = AccentBlue,
                        modifier = Modifier.size(18.dp)
                    )
                }

                AnimatedVisibility(visible = showAdvanced) {
                    Column {
                        HorizontalDivider(color = BorderColor)
                        ToggleRow(stringResource(R.string.toggle_nr_sa_title), stringResource(R.string.toggle_nr_sa_desc), config.nrSaUnlock) {
                            updateConfig(config.copy(nrSaUnlock = it, overrideEnabled = true))
                        }
                        ToggleRow(stringResource(R.string.toggle_ca_lte_plus_title), stringResource(R.string.toggle_ca_lte_plus_desc), config.carrierAggregation) {
                            updateConfig(config.copy(carrierAggregation = it, overrideEnabled = true))
                        }
                        ToggleRow(stringResource(R.string.toggle_vilte_title), stringResource(R.string.toggle_vilte_desc), config.vilte) {
                            updateConfig(config.copy(vilte = it, overrideEnabled = true))
                        }
                        ToggleRow(stringResource(R.string.toggle_sms_ims_title), stringResource(R.string.toggle_sms_ims_desc), config.smsOverIms) {
                            updateConfig(config.copy(smsOverIms = it, overrideEnabled = true))
                        }
                        ToggleRow(stringResource(R.string.toggle_ims_call_features_title), stringResource(R.string.toggle_ims_call_features_desc), config.imsCallFeatures) {
                            updateConfig(config.copy(imsCallFeatures = it, overrideEnabled = true))
                        }
                        ToggleRow(stringResource(R.string.toggle_show_ims_status_title), stringResource(R.string.toggle_show_ims_status_desc), config.showImsStatus) {
                            updateConfig(config.copy(showImsStatus = it, overrideEnabled = true))
                        }
                        ToggleRow(stringResource(R.string.toggle_show_4g_icon_title), stringResource(R.string.toggle_show_4g_icon_desc), config.show4gIcon) {
                            updateConfig(config.copy(show4gIcon = it, overrideEnabled = true))
                        }
                        ToggleRow(stringResource(R.string.toggle_allow_apn_title), stringResource(R.string.toggle_allow_apn_desc), config.allowApnEdit) {
                            updateConfig(config.copy(allowApnEdit = it, overrideEnabled = true))
                        }
                        ToggleRow(stringResource(R.string.toggle_auto_data_switch_title), stringResource(R.string.toggle_auto_data_switch_desc), config.autoDataSwitch) {
                            updateConfig(config.copy(autoDataSwitch = it, overrideEnabled = true))
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        if (!config.overrideEnabled) {
                            updateConfig(config.copy(overrideEnabled = true))
                        }
                        onApplySlot()
                    },
                    enabled = !isApplying,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryGradEnd)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.btn_apply_sim, slotIndex + 1), fontSize = 12.sp)
                }

                OutlinedButton(
                    onClick = {
                        updateConfig(config.copy(overrideEnabled = false))
                        onRestoreSlot()
                    },
                    enabled = !isApplying,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentRed),
                    border = BorderStroke(1.dp, AccentRed.copy(alpha = 0.5f))
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.btn_restore_sim, slotIndex + 1), fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun StatusChip(text: String, bgColor: Color, textColor: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bgColor)
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            text = text,
            color = textColor,
            fontWeight = FontWeight.SemiBold,
            fontSize = 10.sp
        )
    }
}

@Composable
private fun WfcModeSelector(
    label: String,
    selectedMode: Int,
    onModeSelected: (Int) -> Unit
) {
    val options = listOf(
        WfcMode.DEFAULT to stringResource(R.string.wfc_mode_default),
        WfcMode.WIFI_PREFERRED to stringResource(R.string.wfc_mode_wifi_pref),
        WfcMode.CELLULAR_PREFERRED to stringResource(R.string.wfc_mode_cell_pref),
        WfcMode.WIFI_ONLY to stringResource(R.string.wfc_mode_wifi_only)
    )
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(label, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = TextLight)
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            for ((mode, title) in options) {
                val selected = selectedMode == mode
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (selected) PrimaryGradEnd else SurfaceSubtle)
                        .border(1.dp, if (selected) AccentBlue else BorderColor, RoundedCornerShape(8.dp))
                        .clickable { onModeSelected(mode) }
                        .padding(vertical = 7.dp, horizontal = 4.dp)
                ) {
                    Text(
                        text = title,
                        color = if (selected) TextLight else TextMuted,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
fun ToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = TextLight)
            Text(description, color = TextMuted, fontSize = 11.sp, lineHeight = 14.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = TextLight,
                checkedTrackColor = PrimaryGradEnd
            )
        )
    }
}

@Composable
fun LocalAdbCard(
    recheckSignal: MutableState<Long>,
    status0: SimLiveStatus,
    status1: SimLiveStatus,
    portInput: String,
    onPortInputChange: (String) -> Unit,
    isApplying: Boolean,
    onApplyClick: () -> Unit,
    onRestoreClick: () -> Unit
) {
    val context = LocalContext.current
    var pairingPortInput by remember { mutableStateOf("") }
    var showManualPorts by remember { mutableStateOf(false) }
    var isAuthorized by remember { mutableStateOf(false) }
    var isWifiConnected by remember { mutableStateOf(false) }

    LaunchedEffect(portInput) {
        val port = portInput.toIntOrNull()
        if (port != null && port > 0 && port <= 65535) {
            withContext(Dispatchers.IO) {
                try {
                    Kadb.create("127.0.0.1", port, 3000, 3000).use { kadb ->
                        val response = kadb.shell("echo 1")
                        isAuthorized = (response.exitCode == 0)
                    }
                } catch (_: Exception) {
                    isAuthorized = false
                }
            }
        } else {
            isAuthorized = false
        }
    }

    DisposableEffect(Unit) {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val caps = connectivityManager.getNetworkCapabilities(network)
                isWifiConnected = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            }

            override fun onLost(network: Network) {
                isWifiConnected = false
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                isWifiConnected = networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            }
        }
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        try {
            connectivityManager.registerNetworkCallback(request, callback)
        } catch (_: Exception) {
        }

        val activeNet = connectivityManager.activeNetwork
        val caps = connectivityManager.getNetworkCapabilities(activeNet)
        isWifiConnected = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true

        onDispose {
            try {
                connectivityManager.unregisterNetworkCallback(callback)
            } catch (_: Exception) {
            }
        }
    }

    val nsdManager = remember { context.getSystemService(Context.NSD_SERVICE) as NsdManager }
    DisposableEffect(Unit) {
        val connectListener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {}
            override fun onDiscoveryStarted(serviceType: String?) {}
            override fun onDiscoveryStopped(serviceType: String?) {}
            override fun onServiceLost(serviceInfo: NsdServiceInfo?) {}
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceType.contains("adb-tls-connect")) {
                    @Suppress("DEPRECATION")
                    nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {}
                        override fun onServiceResolved(resolvedServiceInfo: NsdServiceInfo) {
                            onPortInputChange(resolvedServiceInfo.port.toString())
                        }
                    })
                }
            }
        }

        val pairingListener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {}
            override fun onDiscoveryStarted(serviceType: String?) {}
            override fun onDiscoveryStopped(serviceType: String?) {}
            override fun onServiceLost(serviceInfo: NsdServiceInfo?) {}
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceType.contains("adb-tls-pairing")) {
                    @Suppress("DEPRECATION")
                    nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {}
                        override fun onServiceResolved(resolvedServiceInfo: NsdServiceInfo) {
                            val port = resolvedServiceInfo.port
                            pairingPortInput = port.toString()
                            MainActivity.pairingPort = port
                        }
                    })
                }
            }
        }

        try {
            nsdManager.discoverServices("_adb-tls-connect._tcp", NsdManager.PROTOCOL_DNS_SD, connectListener)
        } catch (_: Exception) {
        }
        try {
            nsdManager.discoverServices("_adb-tls-pairing._tcp", NsdManager.PROTOCOL_DNS_SD, pairingListener)
        } catch (_: Exception) {
        }

        onDispose {
            try { nsdManager.stopServiceDiscovery(connectListener) } catch (_: Exception) {}
            try { nsdManager.stopServiceDiscovery(pairingListener) } catch (_: Exception) {}
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, BorderColor, RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = CardBackground)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                stringResource(R.string.card_activation_title),
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                color = TextLight
            )
            Spacer(modifier = Modifier.height(12.dp))

            // Step 1: Wi-Fi
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.step1_title), fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = TextLight)
                    Text(stringResource(R.string.step1_desc), fontSize = 10.sp, color = TextMuted)
                }
                StatusChip(
                    text = if (isWifiConnected) stringResource(R.string.status_connected) else stringResource(R.string.status_not_connected),
                    bgColor = if (isWifiConnected) AccentGreen.copy(alpha = 0.15f) else AccentRed.copy(alpha = 0.15f),
                    textColor = if (isWifiConnected) AccentGreen else AccentRed
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = BorderColor)
            Spacer(modifier = Modifier.height(12.dp))

            // Step 2: Wireless Debugging
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.step2_title), fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = TextLight)
                    Text(
                        text = if (isAuthorized) stringResource(R.string.step2_status_authorized) else if (portInput.isNotEmpty()) stringResource(R.string.step2_status_needs_pairing) else stringResource(R.string.step2_status_prompt),
                        fontSize = 10.sp,
                        color = if (isAuthorized) AccentGreen else if (portInput.isNotEmpty()) AccentOrange else TextMuted
                    )
                }
                StatusChip(
                    text = if (isAuthorized) stringResource(R.string.badge_authorized) else if (portInput.isNotEmpty()) stringResource(R.string.badge_unpaired) else stringResource(R.string.status_not_connected),
                    bgColor = if (isAuthorized) AccentGreen.copy(alpha = 0.15f) else if (portInput.isNotEmpty()) AccentOrange.copy(alpha = 0.15f) else BorderColor.copy(alpha = 0.3f),
                    textColor = if (isAuthorized) AccentGreen else if (portInput.isNotEmpty()) AccentOrange else TextMuted
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    (context as? MainActivity)?.requestNotificationPermissionAndShow()
                    try {
                        context.startActivity(Intent("android.settings.WIRELESS_DEBUGGING_SETTINGS"))
                    } catch (_: Exception) {
                        try {
                            context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                        } catch (_: Exception) {
                            Toast.makeText(context, context.getString(R.string.toast_developer_options_not_found), Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextLight),
                border = BorderStroke(1.dp, BorderColor)
            ) {
                Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.btn_open_wireless_debugging), fontSize = 11.sp)
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = BorderColor)
            Spacer(modifier = Modifier.height(12.dp))

            // Step 3: Global Summary & Apply Enabled SIMs
            Text(stringResource(R.string.step3_title), fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = TextLight)
            Spacer(modifier = Modifier.height(4.dp))
            Text(stringResource(R.string.step3_desc), fontSize = 10.sp, color = TextMuted)
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SimSummaryBox(
                    title = status0.carrierName.ifBlank { stringResource(R.string.sim1_config_title) },
                    status = status0,
                    modifier = Modifier.weight(1f)
                )
                SimSummaryBox(
                    title = status1.carrierName.ifBlank { stringResource(R.string.sim2_config_title) },
                    status = status1,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(10.dp))
            val hasResolvedPort = portInput.isNotEmpty()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onApplyClick,
                    enabled = !isApplying,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = if (hasResolvedPort) AccentGreen else BorderColor)
                ) {
                    if (isApplying) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), color = Color.White, strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            if (hasResolvedPort) stringResource(R.string.btn_activate) else stringResource(R.string.btn_waiting),
                            fontSize = 12.sp
                        )
                    }
                }

                OutlinedButton(
                    onClick = onRestoreClick,
                    enabled = !isApplying,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentRed),
                    border = BorderStroke(1.dp, AccentRed.copy(alpha = 0.5f))
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.btn_restore), fontSize = 12.sp)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.hint_ims_update),
                fontSize = 10.sp,
                color = AccentOrange,
                modifier = Modifier.padding(horizontal = 2.dp)
            )

            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                Text(
                    text = if (showManualPorts) stringResource(R.string.toggle_hide_advanced) else stringResource(R.string.toggle_show_advanced),
                    color = TextMuted,
                    fontSize = 10.sp,
                    modifier = Modifier
                        .clickable { showManualPorts = !showManualPorts }
                        .padding(4.dp)
                )
            }

            if (showManualPorts) {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = portInput,
                    onValueChange = { onPortInputChange(it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.label_adb_port), fontSize = 9.sp) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    textStyle = LocalTextStyle.current.copy(fontSize = 11.sp)
                )
            }
        }
    }
}

@Composable
private fun SimSummaryBox(title: String, status: SimLiveStatus, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(BackgroundDark.copy(alpha = 0.5f))
            .border(1.dp, BorderColor, RoundedCornerShape(8.dp))
            .padding(8.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Text(title, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = TextLight, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = if (status.configApplied) stringResource(R.string.status_app_optimized) else stringResource(R.string.status_system_default),
                color = if (status.configApplied) AccentGreen else TextMuted,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp
            )
            Spacer(modifier = Modifier.height(1.dp))
            Text(
                text = if (status.imsRegistered) "IMS: ${status.imsTransport}" else stringResource(R.string.badge_ims_not_registered),
                color = if (status.imsRegistered) AccentGreen else AccentRed,
                fontSize = 9.sp
            )
            if (status.crossSimEnabled) {
                Text(
                    text = "Backup Calling: ON",
                    color = AccentBlue,
                    fontSize = 9.sp
                )
            }
        }
    }
}
