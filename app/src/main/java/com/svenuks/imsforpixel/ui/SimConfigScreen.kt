package com.svenuks.imsforpixel.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.svenuks.imsforpixel.R
import com.svenuks.imsforpixel.SimConfigStore
import com.svenuks.imsforpixel.SimLiveStatus
import com.svenuks.imsforpixel.SimOverrideConfig
import com.svenuks.imsforpixel.SimPreset
import com.svenuks.imsforpixel.WfcMode
import com.svenuks.imsforpixel.ui.theme.StatusBlue
import com.svenuks.imsforpixel.ui.theme.StatusGreen
import com.svenuks.imsforpixel.ui.theme.StatusRed

@Composable
fun SimConfigScreen(
    selectedSlot: Int,
    onSlotSelected: (Int) -> Unit,
    status0: SimLiveStatus,
    status1: SimLiveStatus,
    isApplying: Boolean,
    recheckSignal: Long,
    onRefreshStatus: () -> Unit,
    onApplySlot: (Int) -> Unit,
    onRestoreSlot: (Int) -> Unit
) {
    val context = LocalContext.current
    val prefs = remember(selectedSlot) {
        context.getSharedPreferences(SimConfigStore.PREFS_NAME, Context.MODE_PRIVATE)
    }
    var config by remember(selectedSlot) {
        mutableStateOf(SimConfigStore.loadSimConfig(prefs, selectedSlot))
    }
    var showAdvanced by remember(selectedSlot) { mutableStateOf(false) }

    LaunchedEffect(selectedSlot, recheckSignal) {
        val latestOverride = prefs.getBoolean("override_enabled_slot_$selectedSlot", config.overrideEnabled)
        if (latestOverride != config.overrideEnabled) {
            config = config.copy(overrideEnabled = latestOverride)
        }
    }

    fun updateConfig(newConfig: SimOverrideConfig) {
        config = newConfig
        SimConfigStore.saveSimConfig(prefs, selectedSlot, newConfig, commit = true)
    }

    val liveStatus = if (selectedSlot == 0) status0 else status1
    val carrierLabel = liveStatus.carrierName.ifBlank { "SIM ${selectedSlot + 1}" }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            SimTabs(
                selectedSlot = selectedSlot,
                status0 = status0,
                status1 = status1,
                onSlotSelected = onSlotSelected
            )
        }

        // Master Override & Live Summary Card
        item {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            PillTag(
                                text = if (liveStatus.configApplied) stringResource(R.string.badge_config_applied)
                                else stringResource(R.string.badge_config_default),
                                color = if (liveStatus.configApplied) StatusGreen else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            PillTag(
                                text = if (liveStatus.imsRegistered) "IMS: ${liveStatus.imsTransport}"
                                else stringResource(R.string.badge_ims_not_registered),
                                color = if (liveStatus.imsRegistered) StatusGreen else StatusRed
                            )
                        }
                        IconButton(onClick = onRefreshStatus, enabled = !isApplying, modifier = Modifier.size(30.dp)) {
                            Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.btn_refresh_status), modifier = Modifier.size(18.dp))
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))

                    ToggleRow(
                        title = stringResource(R.string.toggle_override_sim_title),
                        description = stringResource(R.string.toggle_override_sim_desc),
                        checked = config.overrideEnabled
                    ) { enabled ->
                        updateConfig(config.copy(overrideEnabled = enabled))
                    }

                    if (!config.overrideEnabled) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ) {
                            Text(
                                text = stringResource(R.string.notice_sim_untouched),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }
                }
            }
        }

        // Smart Presets Row
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = stringResource(R.string.presets_title),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp)
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val presets = listOf(
                        SimPreset.TRAVEL_BACKUP to stringResource(R.string.preset_travel_backup),
                        SimPreset.UNSUPPORTED_FULL to stringResource(R.string.preset_unsupported_full),
                        SimPreset.VOLTE_VONR_ONLY to stringResource(R.string.preset_volte_vonr),
                        SimPreset.MINIMAL_VOLTE to stringResource(R.string.preset_minimal_volte)
                    )
                    for ((preset, label) in presets) {
                        AssistChip(
                            onClick = {
                                updateConfig(SimConfigStore.applyPreset(preset))
                                Toast.makeText(context, context.getString(R.string.toast_preset_applied, label), Toast.LENGTH_SHORT).show()
                            },
                            label = { Text(label, fontSize = 12.sp) }
                        )
                    }
                }
            }
        }

        // Voice & 5G Group
        item {
            SectionCard(title = stringResource(R.string.section_voice_5g), enabled = config.overrideEnabled) {
                ToggleRow(stringResource(R.string.toggle_volte_title), stringResource(R.string.toggle_volte_desc), config.volte) {
                    updateConfig(config.copy(volte = it, overrideEnabled = true))
                }
                ToggleRow(stringResource(R.string.toggle_vonr_title), stringResource(R.string.toggle_vonr_desc), config.vonr) {
                    updateConfig(config.copy(vonr = it, overrideEnabled = true))
                }
                ToggleRow(stringResource(R.string.toggle_nr_sa_title), stringResource(R.string.toggle_nr_sa_desc), config.nrSaUnlock) {
                    updateConfig(config.copy(nrSaUnlock = it, overrideEnabled = true))
                }
            }
        }

        // Wi-Fi & Backup Calling Group
        item {
            SectionCard(title = stringResource(R.string.section_wifi_backup), enabled = config.overrideEnabled) {
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
            }
        }

        // Advanced Carrier & Android 17 Group
        item {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
            ) {
                Column(
                    modifier = Modifier
                        .padding(14.dp)
                        .alpha(if (config.overrideEnabled) 1f else 0.5f)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showAdvanced = !showAdvanced },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (showAdvanced) stringResource(R.string.section_advanced_ims_hide) else stringResource(R.string.section_advanced_ims_show),
                            color = StatusBlue,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp
                        )
                        Icon(
                            imageVector = if (showAdvanced) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = null,
                            tint = StatusBlue
                        )
                    }

                    AnimatedVisibility(visible = showAdvanced) {
                        Column(modifier = Modifier.padding(top = 8.dp)) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
                            ToggleRow(stringResource(R.string.toggle_ss_ut_title), stringResource(R.string.toggle_ss_ut_desc), config.ssUt) {
                                updateConfig(config.copy(ssUt = it, overrideEnabled = true))
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
                            ToggleRow(stringResource(R.string.toggle_protect_2g_title), stringResource(R.string.toggle_protect_2g_desc), config.protect2g) {
                                updateConfig(config.copy(protect2g = it, overrideEnabled = true))
                            }
                            ToggleRow(stringResource(R.string.toggle_auto_data_switch_title), stringResource(R.string.toggle_auto_data_switch_desc), config.autoDataSwitch) {
                                updateConfig(config.copy(autoDataSwitch = it, overrideEnabled = true))
                            }
                        }
                    }
                }
            }
        }

        // Bottom Apply / Restore Actions for this SIM
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = {
                        if (!config.overrideEnabled) {
                            updateConfig(config.copy(overrideEnabled = true))
                        }
                        onApplySlot(selectedSlot)
                    },
                    enabled = !isApplying,
                    modifier = Modifier.weight(1f)
                ) {
                    if (isApplying) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    } else {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.btn_apply_carrier, carrierLabel), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }

                OutlinedButton(
                    onClick = {
                        updateConfig(config.copy(overrideEnabled = false))
                        onRestoreSlot(selectedSlot)
                    },
                    enabled = !isApplying,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = StatusRed),
                    border = BorderStroke(1.dp, StatusRed.copy(alpha = 0.5f))
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.btn_restore_carrier, carrierLabel), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun SimTabs(
    selectedSlot: Int,
    status0: SimLiveStatus,
    status1: SimLiveStatus,
    onSlotSelected: (Int) -> Unit
) {
    val label0 = if (status0.carrierName.isNotBlank()) {
        "SIM 1 · ${status0.carrierName} (${if (status0.isEmbedded) "eSIM" else "pSIM"})"
    } else stringResource(R.string.sim_slot_1)
    val label1 = if (status1.carrierName.isNotBlank()) {
        "SIM 2 · ${status1.carrierName} (${if (status1.isEmbedded) "eSIM" else "pSIM"})"
    } else stringResource(R.string.sim_slot_2)

    TabRow(
        selectedTabIndex = selectedSlot,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        indicator = { tabPositions ->
            TabRowDefaults.SecondaryIndicator(
                Modifier.tabIndicatorOffset(tabPositions[selectedSlot]),
                color = MaterialTheme.colorScheme.primary
            )
        },
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
    ) {
        Tab(
            selected = selectedSlot == 0,
            onClick = { onSlotSelected(0) },
            text = { Text(label0, fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        )
        Tab(
            selected = selectedSlot == 1,
            onClick = { onSlotSelected(1) },
            text = { Text(label1, fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        )
    }
}

@Composable
private fun SectionCard(
    title: String,
    enabled: Boolean,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
    ) {
        Column(
            modifier = Modifier
                .padding(14.dp)
                .alpha(if (enabled) 1f else 0.5f)
        ) {
            Text(
                text = title,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(4.dp))
            content()
        }
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
        Text(label, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
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
                        .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                        .border(
                            1.dp,
                            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                            RoundedCornerShape(8.dp)
                        )
                        .clickable { onModeSelected(mode) }
                        .padding(vertical = 7.dp, horizontal = 4.dp)
                ) {
                    Text(
                        text = title,
                        color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
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
        Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, lineHeight = 14.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}
