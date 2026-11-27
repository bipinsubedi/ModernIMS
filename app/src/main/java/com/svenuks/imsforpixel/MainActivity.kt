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
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
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
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import com.flyfishxu.kadb.Kadb
import com.svenuks.imsforpixel.ui.DashboardScreen
import com.svenuks.imsforpixel.ui.DiagnosticsScreen
import com.svenuks.imsforpixel.ui.SimConfigScreen
import com.svenuks.imsforpixel.ui.theme.ImsForPixelTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_AUTO_APPLY = "extra_auto_apply"
        const val EXTRA_START_WATCHER_AND_OPEN_DEBUG = "extra_start_watcher_open_debug"
        const val EXTRA_OPEN_TAB = "extra_open_tab"

        @JvmStatic
        var pairingPort: Int? = null
        var onAuthStatusChanged: (() -> Unit)? = null
        var onInstrumentDone: ((action: String, success: Boolean) -> Unit)? = null
    }

    private val actionPair: String
        get() = "$packageName.ACTION_PAIR"

    private var pairingReceiver: BroadcastReceiver? = null
    private var showPairingOnPermissionGrant = false
    private val launchFlagsSignal = mutableStateOf(0L)
    private var pendingAutoApply by mutableStateOf(false)
    private var pendingTab by mutableStateOf<String?>(null)

    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            if (showPairingOnPermissionGrant) {
                showPairingNotification()
            }
        } else if (showPairingOnPermissionGrant) {
            Toast.makeText(this, getString(R.string.notification_permission_required), Toast.LENGTH_LONG).show()
        }
        showPairingOnPermissionGrant = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        try {
            HiddenApiBypass.addHiddenApiExemptions("L")
        } catch (e: Exception) {
            Log.e("ModernIMS", "Failed to apply HiddenApiBypass exemptions", e)
        }

        LocalAdbManager.initCertStore(this)
        val prefs = getSharedPreferences(SimConfigStore.PREFS_NAME, Context.MODE_PRIVATE)
        SimConfigStore.ensureDefaults(prefs)
        registerDynamicShortcuts()
        handleIntentExtras(intent)

        val filter = IntentFilter(actionPair)
        pairingReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == actionPair) {
                    val remoteInput = RemoteInput.getResultsFromIntent(intent)
                    val code = remoteInput?.getCharSequence("extra_pairing_code")?.toString()?.trim()
                    if (!code.isNullOrEmpty()) {
                        handleNotificationPairing(code)
                    }
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(pairingReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(pairingReceiver, filter)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            showPairingOnPermissionGrant = false
            requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            ImsForPixelTheme {
                val recheckSignal = remember { mutableStateOf(System.currentTimeMillis()) }
                MainAppShell(
                    recheckSignal = recheckSignal,
                    launchFlagsSignal = launchFlagsSignal.value,
                    consumeAutoApply = {
                        val v = pendingAutoApply
                        pendingAutoApply = false
                        v
                    },
                    consumeRequestedTab = {
                        val t = pendingTab
                        pendingTab = null
                        t
                    }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntentExtras(intent)
    }

    private fun handleIntentExtras(intent: Intent?) {
        if (intent == null) return
        if (intent.getBooleanExtra(EXTRA_START_WATCHER_AND_OPEN_DEBUG, false)) {
            AutoApplyService.startWatcher(this)
            Toast.makeText(this, getString(R.string.toast_watcher_started), Toast.LENGTH_SHORT).show()
            try {
                startActivity(Intent("android.settings.WIRELESS_DEBUGGING_SETTINGS"))
            } catch (_: Exception) {
                try {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                } catch (_: Exception) {
                }
            }
        }
        if (intent.getBooleanExtra(EXTRA_AUTO_APPLY, false)) {
            pendingAutoApply = true
        }
        intent.getStringExtra(EXTRA_OPEN_TAB)?.let { tab ->
            pendingTab = tab
        }
        launchFlagsSignal.value = System.currentTimeMillis()
    }

    private fun registerDynamicShortcuts() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) return
        try {
            val shortcutManager = getSystemService(ShortcutManager::class.java) ?: return
            val quickReapplyIntent = Intent(this, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                putExtra(EXTRA_AUTO_APPLY, true)
            }
            val diagIntent = Intent(this, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                putExtra(EXTRA_OPEN_TAB, "diagnostics")
            }
            val s1 = ShortcutInfo.Builder(this, "quick_reapply")
                .setShortLabel(getString(R.string.shortcut_quick_reapply_short))
                .setLongLabel(getString(R.string.shortcut_quick_reapply_long))
                .setIcon(Icon.createWithResource(this, R.drawable.ic_tile_ims))
                .setIntent(quickReapplyIntent)
                .build()
            val s2 = ShortcutInfo.Builder(this, "open_diagnostics")
                .setShortLabel(getString(R.string.nav_diagnostics))
                .setLongLabel(getString(R.string.shortcut_diag_long))
                .setIcon(Icon.createWithResource(this, R.drawable.ic_tile_ims))
                .setIntent(diagIntent)
                .build()
            shortcutManager.dynamicShortcuts = listOf(s1, s2)
        } catch (_: Exception) {
        }
    }

    fun requestNotificationPermissionAndShow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                showPairingOnPermissionGrant = true
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                showPairingNotification()
            }
        } else {
            showPairingNotification()
        }
    }

    fun showImsStatusNotification(isActivate: Boolean) {
        val channelId = "ims_status_channel"
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(channelId, getString(R.string.notification_channel_ims_status), NotificationManager.IMPORTANCE_HIGH)
            manager.createNotificationChannel(ch)
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
            .setSmallIcon(R.drawable.ic_tile_ims)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        manager.notify(203, notification)
    }

    private fun createPairingChannel() {
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
        createPairingChannel()
        val remoteInput = RemoteInput.Builder("extra_pairing_code")
            .setLabel(getString(R.string.notification_pairing_reply_label))
            .build()
        val intent = Intent(actionPair).apply { `package` = packageName }
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
        ).addRemoteInput(remoteInput).build()

        val notification = NotificationCompat.Builder(this, "pairing_channel")
            .setSmallIcon(R.drawable.ic_tile_ims)
            .setContentTitle(getString(R.string.notification_channel_pairing))
            .setContentText(getString(R.string.notification_pairing_prompt))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .setAutoCancel(false)
            .addAction(action)
            .build()
        getSystemService(NotificationManager::class.java)?.notify(202, notification)
    }

    private fun showPairingStatusNotification(text: String) {
        val notification = NotificationCompat.Builder(this, "pairing_channel")
            .setSmallIcon(R.drawable.ic_tile_ims)
            .setContentTitle(getString(R.string.notification_channel_pairing))
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java)?.notify(202, notification)
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
            val result = LocalAdbManager.pair(this@MainActivity, port, code)
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
            try { unregisterReceiver(it) } catch (_: Exception) {}
        }
        getSystemService(NotificationManager::class.java)?.cancel(202)
    }
}

private enum class AppDestination {
    HOME, CONFIGURE, DIAGNOSTICS
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppShell(
    recheckSignal: MutableState<Long>,
    launchFlagsSignal: Long,
    consumeAutoApply: () -> Boolean,
    consumeRequestedTab: () -> String?
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences(SimConfigStore.PREFS_NAME, Context.MODE_PRIVATE) }

    var currentTab by remember { mutableStateOf(AppDestination.HOME) }
    var selectedSimSlot by remember { mutableStateOf(0) }
    var portInput by remember { mutableStateOf("") }
    var isAuthorized by remember { mutableStateOf(false) }
    var isWifiConnected by remember { mutableStateOf(false) }
    var isApplying by remember { mutableStateOf(false) }
    var needsReapplyAfterBoot by remember { mutableStateOf(SimConfigStore.isNeedsReapplyAfterBoot(prefs)) }
    val isInstrumenting = remember { AtomicBoolean(false) }
    var autoReapplyAttempted by remember { mutableStateOf(false) }
    var quickReapplyRequested by remember { mutableStateOf(false) }

    var status0 by remember { mutableStateOf(SimConfigStore.loadLiveStatus(context, 0)) }
    var status1 by remember { mutableStateOf(SimConfigStore.loadLiveStatus(context, 1)) }
    var captivePortal by remember { mutableStateOf(SimConfigStore.loadCaptivePortalState(context)) }

    LaunchedEffect(launchFlagsSignal) {
        when (consumeRequestedTab()) {
            "diagnostics" -> currentTab = AppDestination.DIAGNOSTICS
            "configure" -> currentTab = AppDestination.CONFIGURE
        }
        if (consumeAutoApply()) {
            autoReapplyAttempted = false
            quickReapplyRequested = true
        }
    }

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
        captivePortal = SimConfigStore.loadCaptivePortalState(context)
        needsReapplyAfterBoot = SimConfigStore.isNeedsReapplyAfterBoot(prefs)
    }

    DisposableEffect(Unit) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                isWifiConnected = cm.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            }
            override fun onLost(network: Network) {
                isWifiConnected = false
            }
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                isWifiConnected = networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            }
        }
        val req = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        try { cm.registerNetworkCallback(req, callback) } catch (_: Exception) {}
        isWifiConnected = cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        onDispose {
            try { cm.unregisterNetworkCallback(callback) } catch (_: Exception) {}
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
                            portInput = resolvedServiceInfo.port.toString()
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
                            MainActivity.pairingPort = resolvedServiceInfo.port
                        }
                    })
                }
            }
        }
        try { nsdManager.discoverServices("_adb-tls-connect._tcp", NsdManager.PROTOCOL_DNS_SD, connectListener) } catch (_: Exception) {}
        try { nsdManager.discoverServices("_adb-tls-pairing._tcp", NsdManager.PROTOCOL_DNS_SD, pairingListener) } catch (_: Exception) {}
        onDispose {
            try { nsdManager.stopServiceDiscovery(connectListener) } catch (_: Exception) {}
            try { nsdManager.stopServiceDiscovery(pairingListener) } catch (_: Exception) {}
        }
    }

    fun runAction(action: String, targetSlot: Int, extraArgs: Map<String, String> = emptyMap()) {
        val port = portInput.toIntOrNull()
        if (port == null || port !in 1..65535) {
            Toast.makeText(context, context.getString(R.string.toast_enable_wireless_debugging), Toast.LENGTH_SHORT).show()
            return
        }

        if (action == "apply" && targetSlot == -1 && !SimConfigStore.hasAnyOverrideEnabled(prefs)) {
            Toast.makeText(context, context.getString(R.string.toast_no_sim_override_enabled), Toast.LENGTH_LONG).show()
            return
        }

        isApplying = true
        isInstrumenting.set(true)
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                LocalAdbManager.runInstrumentation(context, port, action, targetSlot, extraArgs)
            }
            isApplying = false
            isInstrumenting.set(false)
            result.fold(
                onSuccess = {
                    val toastRes = when (action) {
                        "fix_apn" -> R.string.toast_apn_fixed
                        "reset_ims" -> R.string.toast_ims_restarted
                        "persistent_volte_enable" -> R.string.toast_persistent_enabled
                        "persistent_volte_restore" -> R.string.toast_persistent_restored
                        "captive_portal_write" -> R.string.toast_captive_applied
                        "captive_portal_reset" -> R.string.toast_captive_reset
                        else -> null
                    }
                    if (toastRes != null) {
                        Toast.makeText(context, context.getString(toastRes), Toast.LENGTH_SHORT).show()
                    }
                    MainActivity.onInstrumentDone?.invoke(action, true)
                },
                onFailure = { error ->
                    val resId = if (action == "clear") R.string.toast_restore_failed else R.string.toast_activation_failed
                    Toast.makeText(context, context.getString(resId, error.message ?: ""), Toast.LENGTH_LONG).show()
                }
            )
        }
    }

    LaunchedEffect(portInput) {
        val port = portInput.toIntOrNull()
        if (port == null || port !in 1..65535) {
            isAuthorized = false
            return@LaunchedEffect
        }
        withContext(Dispatchers.IO) {
            isAuthorized = LocalAdbManager.checkAuthorized(port)
            var activeKadb: Kadb? = null
            try {
                while (true) {
                    if (isInstrumenting.get()) {
                        delay(1000)
                        continue
                    }
                    try {
                        val kadb = activeKadb ?: Kadb.create("127.0.0.1", port, 5000, 5000).also { activeKadb = it }
                        isAuthorized = true
                        LocalAdbManager.pollImsQueryTool(context, kadb)
                    } catch (_: Exception) {
                        isAuthorized = false
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

    // Boot Reminder "Quick Re-Apply" action / shortcut handler:
    // Waits briefly for Wireless ADB authorization; if still OFF, launches Auto-Apply Watcher & opens Wireless Debugging
    LaunchedEffect(quickReapplyRequested) {
        if (!quickReapplyRequested) return@LaunchedEffect
        var waitedMs = 0
        while (waitedMs < 2500 && !(isAuthorized && portInput.isNotBlank())) {
            delay(250)
            waitedMs += 250
        }
        quickReapplyRequested = false
        if (isAuthorized && portInput.isNotBlank() && !isApplying) {
            autoReapplyAttempted = true
            Toast.makeText(context, context.getString(R.string.toast_auto_reapplying), Toast.LENGTH_SHORT).show()
            runAction("apply", -1)
        } else {
            val port = portInput.toIntOrNull() ?: -1
            AutoApplyService.startWatcher(context, port)
            Toast.makeText(context, context.getString(R.string.toast_watcher_started), Toast.LENGTH_LONG).show()
            try {
                context.startActivity(Intent("android.settings.WIRELESS_DEBUGGING_SETTINGS"))
            } catch (_: Exception) {
                try {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                } catch (_: Exception) {
                }
            }
        }
    }

    // Auto-reapply on launch if device rebooted and Wireless ADB is authorized
    LaunchedEffect(isAuthorized, needsReapplyAfterBoot) {
        if (isAuthorized && needsReapplyAfterBoot && !autoReapplyAttempted && !quickReapplyRequested &&
            SimConfigStore.isAutoApplyOnLaunch(prefs) && SimConfigStore.hasAnyOverrideEnabled(prefs) && !isApplying
        ) {
            delay(300)
            if (isAuthorized && needsReapplyAfterBoot && !autoReapplyAttempted && !quickReapplyRequested && !isApplying) {
                autoReapplyAttempted = true
                Toast.makeText(context, context.getString(R.string.toast_auto_reapplying), Toast.LENGTH_SHORT).show()
                runAction("apply", -1)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.app_name),
                        fontWeight = FontWeight.Bold,
                        fontSize = 19.sp
                    )
                },
                actions = {
                    if (isApplying) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(end = 16.dp).size(18.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        IconButton(onClick = { runAction("query", -1) }) {
                            Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.btn_refresh_status))
                        }
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = currentTab == AppDestination.HOME,
                    onClick = { currentTab = AppDestination.HOME },
                    icon = { Icon(Icons.Default.Home, contentDescription = null) },
                    label = { Text(stringResource(R.string.nav_home)) }
                )
                NavigationBarItem(
                    selected = currentTab == AppDestination.CONFIGURE,
                    onClick = { currentTab = AppDestination.CONFIGURE },
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    label = { Text(stringResource(R.string.nav_configure)) }
                )
                NavigationBarItem(
                    selected = currentTab == AppDestination.DIAGNOSTICS,
                    onClick = { currentTab = AppDestination.DIAGNOSTICS },
                    icon = { Icon(Icons.Default.Info, contentDescription = null) },
                    label = { Text(stringResource(R.string.nav_diagnostics)) }
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (currentTab) {
                AppDestination.HOME -> DashboardScreen(
                    status0 = status0,
                    status1 = status1,
                    isWifiConnected = isWifiConnected,
                    isAuthorized = isAuthorized,
                    needsReapplyAfterBoot = needsReapplyAfterBoot,
                    portInput = portInput,
                    onPortInputChange = { portInput = it },
                    isApplying = isApplying,
                    onConfigureSlot = { slot ->
                        selectedSimSlot = slot
                        currentTab = AppDestination.CONFIGURE
                    },
                    onApplySlot = { slot -> runAction("apply", slot) },
                    onRestoreSlot = { slot -> runAction("clear", slot) },
                    onFixApnSlot = { slot -> runAction("fix_apn", slot) },
                    onApplyAllEnabled = { runAction("apply", -1) },
                    onRestoreAll = { runAction("clear", -1) },
                    onRestartIms = { runAction("reset_ims", -1) },
                    onRefreshStatus = { runAction("query", -1) }
                )
                AppDestination.CONFIGURE -> SimConfigScreen(
                    selectedSlot = selectedSimSlot,
                    onSlotSelected = { selectedSimSlot = it },
                    status0 = status0,
                    status1 = status1,
                    isApplying = isApplying,
                    recheckSignal = recheckSignal.value,
                    onRefreshStatus = { runAction("query", -1) },
                    onApplySlot = { slot -> runAction("apply", slot) },
                    onRestoreSlot = { slot -> runAction("clear", slot) }
                )
                AppDestination.DIAGNOSTICS -> DiagnosticsScreen(
                    status0 = status0,
                    status1 = status1,
                    captivePortal = captivePortal,
                    portInput = portInput,
                    onPortInputChange = { portInput = it },
                    isApplying = isApplying,
                    recheckSignal = recheckSignal.value,
                    onRefreshStatus = { runAction("query", -1) },
                    onFixApnSlot = { slot -> runAction("fix_apn", slot) },
                    onRestartImsSlot = { slot -> runAction("reset_ims", slot) },
                    onPersistentVolteEnable = { slot -> runAction("persistent_volte_enable", slot) },
                    onPersistentVolteRestore = { slot -> runAction("persistent_volte_restore", slot) },
                    onCaptivePortalWrite = { http, https ->
                        runAction("captive_portal_write", -1, mapOf("http_url" to http, "https_url" to https))
                    },
                    onCaptivePortalReset = { runAction("captive_portal_reset", -1) }
                )
            }
        }
    }
}
