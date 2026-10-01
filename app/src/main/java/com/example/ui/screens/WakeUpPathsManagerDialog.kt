package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.InstalledAppItem
import com.example.ui.components.AppIconImage
import com.example.ui.viewmodel.PureStopViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WakeUpPathsManagerDialog(
    viewModel: PureStopViewModel,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val managedApps by viewModel.allManagedApps.collectAsState()
    val detectedWakeUpEvents by viewModel.detectedWakeUpEvents.collectAsState()

    var searchQuery by remember { mutableStateOf("") }
    var currentFilter by remember { mutableStateOf("ALL") } // ALL, MONITORING, DETECTED

    val detectedPkgsMap = remember(detectedWakeUpEvents) {
        val map = mutableMapOf<String, Int>()
        for (ev in detectedWakeUpEvents.filter { !it.isCut }) {
            map[ev.packageName] = (map[ev.packageName] ?: 0) + 1
        }
        map
    }

    val filteredApps = remember(managedApps, searchQuery, currentFilter, detectedPkgsMap) {
        managedApps.filter { app ->
            val matchesSearch = searchQuery.isBlank() ||
                app.appName.contains(searchQuery, ignoreCase = true) ||
                app.packageName.contains(searchQuery, ignoreCase = true)

            val isMonitored = app.isWakeUpMonitoringEnabled
            val isDetected = (detectedPkgsMap[app.packageName] ?: 0) > 0

            val matchesFilter = when (currentFilter) {
                "MONITORING" -> isMonitored
                "DETECTED" -> isDetected
                else -> true
            }

            matchesSearch && matchesFilter
        }
    }

    val monitoredCount = managedApps.count { it.isWakeUpMonitoringEnabled }
    val detectedAppsCount = detectedPkgsMap.size
    val totalAppsCount = managedApps.size

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = {
            BottomSheetDefaults.DragHandle()
        },
        containerColor = Color.White,
        tonalElevation = 0.dp,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFFEF3C7)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Bolt,
                        contentDescription = "Wake-Up Monitor",
                        tint = Color(0xFFD97706),
                        modifier = Modifier.size(24.dp)
                    )
                }

                Spacer(modifier = Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Wake-Up Path Monitor",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (monitoredCount > 0) "$monitoredCount of $totalAppsCount apps actively monitored" else "Tick on apps to trace background autostarts",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close Dialog")
                }
            }

            // Explanatory Banner
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFFFFFBEB)
                ),
                shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFDE68A))
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Bolt,
                        contentDescription = null,
                        tint = Color(0xFFD97706),
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Tick ON 'Monitor' for any app to catch secret background wake-ups (network, sync, providers). Click any app to see its detected wake-up path.",
                        fontSize = 12.sp,
                        color = Color(0xFF92400E),
                        lineHeight = 16.sp
                    )
                }
            }

            // Search Bar & Filter Controls
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search apps...", fontSize = 13.sp) },
                    leadingIcon = {
                        Icon(Icons.Default.Search, contentDescription = "Search", modifier = Modifier.size(18.dp))
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Close, contentDescription = "Clear", modifier = Modifier.size(16.dp))
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = Color.Transparent
                    )
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Filter Chips
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilterChip(
                        selected = currentFilter == "ALL",
                        onClick = { currentFilter = "ALL" },
                        label = { Text("All ($totalAppsCount)", fontSize = 12.sp) },
                        shape = RoundedCornerShape(8.dp)
                    )
                    FilterChip(
                        selected = currentFilter == "MONITORING",
                        onClick = { currentFilter = "MONITORING" },
                        label = { Text("Monitoring ($monitoredCount)", fontSize = 12.sp, fontWeight = FontWeight.SemiBold) },
                        shape = RoundedCornerShape(8.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFFDCFCE7),
                            selectedLabelColor = Color(0xFF166534)
                        )
                    )
                    if (detectedAppsCount > 0) {
                        FilterChip(
                            selected = currentFilter == "DETECTED",
                            onClick = { currentFilter = "DETECTED" },
                            label = { Text("Detected ($detectedAppsCount)", fontSize = 12.sp, fontWeight = FontWeight.Bold) },
                            shape = RoundedCornerShape(8.dp),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFFFEF3C7),
                                selectedLabelColor = Color(0xFF92400E)
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Batch action row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val allMonitored = filteredApps.isNotEmpty() && filteredApps.all { it.isWakeUpMonitoringEnabled }
                    Text(
                        text = if (filteredApps.size == 1) "1 app" else "${filteredApps.size} apps • Tap to inspect",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    OutlinedButton(
                        onClick = {
                            val targetState = !allMonitored
                            for (app in filteredApps) {
                                viewModel.setWakeUpMonitoring(app.packageName, targetState)
                            }
                        },
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Text(
                            text = if (allMonitored) "Turn Off All" else "Monitor All",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            // List of Apps
            if (filteredApps.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (searchQuery.isNotEmpty()) "No apps match '$searchQuery'" else "No apps found",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 14.sp
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filteredApps, key = { it.packageName }) { app ->
                        val detectedCount = detectedPkgsMap[app.packageName] ?: 0

                        WakeUpAppMonitorRow(
                            app = app,
                            detectedCount = detectedCount,
                            onClickApp = {
                                viewModel.selectAppForWakeup(app)
                            },
                            onToggleMonitor = { enabled ->
                                viewModel.setWakeUpMonitoring(app.packageName, enabled)
                            }
                        )
                    }
                }
            }
        }
    }
}

/**
 * Clean, modern app card for Wake-Up Path Monitoring.
 * Instead of cluttered internal options, it gives a direct toggle to "Monitor Wake Up Path",
 * and clicking the card opens the Detected Wake-Up Path!
 */
@Composable
private fun WakeUpAppMonitorRow(
    app: InstalledAppItem,
    detectedCount: Int,
    onClickApp: () -> Unit,
    onToggleMonitor: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .border(
                width = 1.dp,
                color = if (detectedCount > 0) Color(0xFFF59E0B).copy(alpha = 0.5f) else Color(0xFFE2E8F0),
                shape = RoundedCornerShape(14.dp)
            )
            .clickable { onClickApp() },
        colors = CardDefaults.cardColors(
            containerColor = if (detectedCount > 0) Color(0xFFFFFDF5) else Color.White
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppIconImage(drawable = app.icon, appName = app.appName, size = 42.dp)
            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = app.appName,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )

                    if (detectedCount > 0) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0xFFFEF3C7))
                                .border(1.dp, Color(0xFFFDE68A), RoundedCornerShape(6.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Bolt,
                                    contentDescription = null,
                                    tint = Color(0xFFD97706),
                                    modifier = Modifier.size(11.dp)
                                )
                                Spacer(modifier = Modifier.width(2.dp))
                                Text(
                                    text = "$detectedCount Caught",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF92400E)
                                )
                            }
                        }
                    } else if (app.isWakeUpMonitoringEnabled) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0xFFDCFCE7))
                                .padding(horizontal = 5.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "Monitoring",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFF166534)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(2.dp))

                Text(
                    text = app.packageName,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(2.dp))

                Text(
                    text = if (detectedCount > 0) "Tap to view detected wake-up path" else if (app.isWakeUpMonitoringEnabled) "Tap to inspect wake-up paths" else "Tick on to monitor wake-ups",
                    fontSize = 11.sp,
                    color = if (detectedCount > 0) Color(0xFFD97706) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }

            Spacer(modifier = Modifier.width(10.dp))

            // Prominent Monitor Switch / Checkbox
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Switch(
                    checked = app.isWakeUpMonitoringEnabled,
                    onCheckedChange = { onToggleMonitor(it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = Color(0xFFD97706),
                        uncheckedThumbColor = Color(0xFF94A3B8),
                        uncheckedTrackColor = Color(0xFFE2E8F0)
                    ),
                    modifier = Modifier.size(width = 46.dp, height = 28.dp)
                )
                Text(
                    text = if (app.isWakeUpMonitoringEnabled) "Monitor On" else "Monitor Off",
                    fontSize = 9.sp,
                    color = if (app.isWakeUpMonitoringEnabled) Color(0xFFD97706) else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(modifier = Modifier.width(4.dp))

            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = "Inspect",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}
