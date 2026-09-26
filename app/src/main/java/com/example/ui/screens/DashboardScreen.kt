package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.OperatingMode
import com.example.model.AppState
import com.example.model.BatchFreezeProgress
import com.example.model.InstalledAppItem
import com.example.ui.components.AppIconImage
import com.example.ui.theme.StateEvading
import com.example.ui.theme.StateForeground
import com.example.ui.theme.StateFree
import com.example.ui.theme.StateFreeBg
import com.example.ui.theme.StateWorking
import com.example.ui.viewmodel.PureStopViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(viewModel: PureStopViewModel) {
    val operatingMode by viewModel.operatingMode.collectAsState()
    val allManagedApps by viewModel.allManagedApps.collectAsState()
    val pendingApps by viewModel.pendingApps.collectAsState()
    val allInstalledApps by viewModel.allInstalledApps.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val showAddAppsSheet by viewModel.showAddAppsSheet.collectAsState()
    val hideSystemAppsInAddList by viewModel.hideSystemAppsInAddList.collectAsState()
    val selectedAppForWakeup by viewModel.selectedAppForWakeup.collectAsState()
    val batchProgress by viewModel.batchProgress.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    var showMenu by remember { mutableStateOf(false) }
    var showModeDialog by remember { mutableStateOf(false) }
    var isSearchExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(statusMessage) {
        statusMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearStatusMessage()
        }
    }

    val runningCount = pendingApps.size

    // Apply search filter strictly to running apps only
    val filteredRunning = remember(pendingApps, searchQuery) {
        if (searchQuery.isBlank()) pendingApps else {
            pendingApps.filter {
                it.appName.contains(searchQuery, ignoreCase = true) ||
                it.packageName.contains(searchQuery, ignoreCase = true)
            }
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.primary),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Bolt,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "ForCify",
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        // Mode Indicator Pill (Root / Non-Root)
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    if (operatingMode == OperatingMode.ROOT)
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                    else
                                        StateFree.copy(alpha = 0.15f)
                                )
                                .border(
                                    width = 1.dp,
                                    color = if (operatingMode == OperatingMode.ROOT)
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                                    else
                                        StateFree.copy(alpha = 0.3f),
                                    shape = RoundedCornerShape(12.dp)
                                )
                                .clickable { showModeDialog = true }
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = if (operatingMode == OperatingMode.ROOT) Icons.Default.Bolt else Icons.Default.Shield,
                                    contentDescription = null,
                                    tint = if (operatingMode == OperatingMode.ROOT) MaterialTheme.colorScheme.primary else StateFree,
                                    modifier = Modifier.size(12.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = if (operatingMode == OperatingMode.ROOT) "Root" else "Non-Root",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (operatingMode == OperatingMode.ROOT) MaterialTheme.colorScheme.primary else StateFree
                                )
                            }
                        }
                    }
                },
                actions = {
                    // Search toggle button
                    IconButton(
                        onClick = {
                            isSearchExpanded = !isSearchExpanded
                            if (!isSearchExpanded) viewModel.setSearchQuery("")
                        }
                    ) {
                        Icon(imageVector = Icons.Default.Search, contentDescription = "Search Apps")
                    }

                    // THE "+" BUTTON ON TOP REQUESTED BY USER!
                    IconButton(
                        onClick = { viewModel.openAddApps() },
                        modifier = Modifier
                            .testTag("add_apps_top_button")
                            .padding(end = 2.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = "Add Apps to Freeze List",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    // Three-dot menu: Contains Refresh, Cut All Wakeups, Switch Mode, Re-run Setup
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(imageVector = Icons.Default.MoreVert, contentDescription = "Options")
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Refresh Status") },
                                onClick = {
                                    showMenu = false
                                    viewModel.refreshApps()
                                },
                                leadingIcon = {
                                    Icon(imageVector = Icons.Default.Refresh, contentDescription = null)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Cut All Wakeups") },
                                onClick = {
                                    showMenu = false
                                    viewModel.cutAllActiveWakeups()
                                },
                                leadingIcon = {
                                    Icon(imageVector = Icons.Default.ContentCut, contentDescription = null)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Switch Operating Mode") },
                                onClick = {
                                    showMenu = false
                                    showModeDialog = true
                                },
                                leadingIcon = {
                                    Icon(imageVector = Icons.Default.Bolt, contentDescription = null)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Re-run Setup Wizard") },
                                onClick = {
                                    showMenu = false
                                    viewModel.resetSetup()
                                },
                                leadingIcon = {
                                    Icon(imageVector = Icons.Default.Settings, contentDescription = null)
                                }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        // THE BOTTOM RIGHT FORCE STOP BUTTON REQUESTED BY USER!
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { viewModel.forceStopAllRunning() },
                containerColor = if (runningCount > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                contentColor = if (runningCount > 0) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 6.dp),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .testTag("force_stop_fab_button")
                    .navigationBarsPadding()
                    .padding(bottom = 12.dp, end = 8.dp),
                icon = {
                    Icon(
                        imageVector = Icons.Default.PowerSettingsNew,
                        contentDescription = "Force Stop Button",
                        modifier = Modifier.size(22.dp)
                    )
                },
                text = {
                    Text(
                        text = if (runningCount > 0) "Force Stop ($runningCount)" else "All Hibernated ✓",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Optional Search Bar with "X" clear button
            AnimatedVisibility(visible = isSearchExpanded) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { viewModel.setSearchQuery(it) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    placeholder = { Text("Filter running apps...") },
                    leadingIcon = {
                        Icon(imageVector = Icons.Default.Search, contentDescription = null)
                    },
                    trailingIcon = if (searchQuery.isNotBlank()) {
                        {
                            IconButton(onClick = { viewModel.setSearchQuery("") }) {
                                Icon(imageVector = Icons.Default.Close, contentDescription = "Clear")
                            }
                        }
                    } else null,
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface
                    )
                )
            }

            // Main Content: Strictly running apps only, no stopped/hibernated lists cluttering the page!
            if (isLoading && allManagedApps.isEmpty()) {
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else if (allManagedApps.isEmpty()) {
                // Empty state when no apps are added yet
                EmptyStateView(
                    isSearchActive = searchQuery.isNotBlank(),
                    onAddApps = { viewModel.openAddApps() }
                )
            } else if (filteredRunning.isEmpty()) {
                // All apps in the freeze list are stopped/hibernated!
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    AllHibernatedCleanCard(
                        managedCount = allManagedApps.size,
                        onAddMore = { viewModel.openAddApps() }
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "NOT HIBERNATING AUTOMATICALLY (${filteredRunning.size})",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "${allManagedApps.size} Managed",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                    items(filteredRunning, key = { it.packageName }) { app ->
                        GreenifyStyleAppCard(
                            app = app,
                            onForceStop = { viewModel.forceStopSingle(app) },
                            onOpenWakeup = { viewModel.selectAppForWakeup(app) },
                            onRemove = { viewModel.removeAppFromFreezeList(app.packageName) }
                        )
                    }
                }
            }
        }
    }

    // Modal Add Apps Sheet with Hide System Apps inside Three-Dot
    if (showAddAppsSheet) {
        AddAppsDialog(
            allApps = allInstalledApps,
            managedPackageNames = allManagedApps.map { it.packageName }.toSet(),
            hideSystemApps = hideSystemAppsInAddList,
            onToggleHideSystemApps = { viewModel.setHideSystemAppsInAddList(it) },
            isLoading = isLoading,
            onDismiss = { viewModel.closeAddApps() },
            onConfirmAdd = { selected ->
                viewModel.addAppsToFreezeList(selected)
            }
        )
    }

    // Wake-Up Inspection & Granular Cut Dialog
    selectedAppForWakeup?.let { app ->
        WakeUpCutDialog(
            app = app,
            operatingMode = operatingMode,
            onDismiss = { viewModel.selectAppForWakeup(null) },
            onTogglePath = { item, path, cut ->
                viewModel.toggleSpecificWakeUpPath(item, path, cut)
            },
            onCutAllPaths = { item ->
                viewModel.cutAllWakeUpPaths(item)
            },
            onRestoreAllPaths = { item ->
                viewModel.restoreAllWakeUpPaths(item)
            },
            onForceStop = { item ->
                viewModel.forceStopSingle(item)
            }
        )
    }

    // Batch Force Stop Progress Dialog
    if (batchProgress.isRunning) {
        BatchProgressDialog(
            progress = batchProgress,
            onDismiss = { viewModel.engine.dismissBatchProgress() }
        )
    }

    // Switch Mode Dialog
    if (showModeDialog) {
        SwitchModeDialog(
            currentMode = operatingMode,
            onSelectMode = { newMode ->
                viewModel.selectOperatingMode(newMode)
                showModeDialog = false
            },
            onDismiss = { showModeDialog = false }
        )
    }
}

/**
 * Greenify-style app card: App name on line 1, exact state and details on line 2, stop action on right
 */
@Composable
private fun GreenifyStyleAppCard(
    app: InstalledAppItem,
    onForceStop: () -> Unit,
    onOpenWakeup: () -> Unit,
    onRemove: () -> Unit
) {
    var showItemMenu by remember { mutableStateOf(false) }

    val stateText = when {
        app.stateDetail.isNotBlank() -> app.stateDetail
        app.state == AppState.EVADING_RESTRICTIONS -> "Running as foreground (evading restrictions)"
        app.state == AppState.FOREGROUND -> "Foreground (Ignored running state)"
        app.state == AppState.WORKING_STATE -> "Background service active"
        else -> app.state.label
    }

    val stateColor = when (app.state) {
        AppState.EVADING_RESTRICTIONS -> StateEvading
        AppState.FOREGROUND -> StateForeground
        AppState.WORKING_STATE -> StateWorking
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
            .clickable(onClick = onOpenWakeup),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppIconImage(drawable = app.icon, appName = app.appName, size = 44.dp)
            Spacer(modifier = Modifier.width(12.dp))

            // Greenify Style Title & Subtitle
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app.appName,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stateText,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = stateColor,
                    maxLines = 1
                )
                if (app.secondaryDetail.isNotBlank()) {
                    Text(
                        text = app.secondaryDetail,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Stop Button on Right
            Button(
                onClick = onForceStop,
                modifier = Modifier
                    .height(34.dp)
                    .testTag("force_stop_${app.packageName}"),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.StopCircle,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Stop",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Box {
                IconButton(onClick = { showItemMenu = true }) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "Options",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                DropdownMenu(
                    expanded = showItemMenu,
                    onDismissRequest = { showItemMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Inspect Wake-Ups") },
                        onClick = {
                            showItemMenu = false
                            onOpenWakeup()
                        },
                        leadingIcon = { Icon(imageVector = Icons.Default.Bolt, contentDescription = null) }
                    )
                    DropdownMenuItem(
                        text = { Text("Remove from List") },
                        onClick = {
                            showItemMenu = false
                            onRemove()
                        },
                        leadingIcon = { Icon(imageVector = Icons.Default.Delete, contentDescription = null) }
                    )
                }
            }
        }
    }
}

@Composable
private fun AllHibernatedCleanCard(
    managedCount: Int,
    onAddMore: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(20.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, StateFree.copy(alpha = 0.35f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(StateFreeBg),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = StateFree,
                    modifier = Modifier.size(44.dp)
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "All Apps Stopped & Hibernated",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "$managedCount apps in your freeze list are currently background-free. Zero RAM or battery wasted.",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                lineHeight = 18.sp
            )
            Spacer(modifier = Modifier.height(20.dp))
            Button(
                onClick = onAddMore,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(imageVector = Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(text = "Add More Apps", fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun EmptyStateView(
    isSearchActive: Boolean,
    onAddApps: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(70.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Bolt,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(36.dp)
            )
        }
        Spacer(modifier = Modifier.height(18.dp))
        Text(
            text = if (isSearchActive) "No Matching Apps" else "No Apps Added Yet",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = if (isSearchActive)
                "Try searching with another name or clear the search query."
            else
                "Add apps to your freeze list to detect running services and force stop them on demand.",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            lineHeight = 18.sp
        )
        Spacer(modifier = Modifier.height(20.dp))
        Button(
            onClick = onAddApps,
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
        ) {
            Icon(imageVector = Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("Add Apps to Freeze List", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun BatchProgressDialog(
    progress: BatchFreezeProgress,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = {},
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Text(
                text = "Force Stopping Apps...",
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Stopping: ${progress.currentAppName}",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = progress.summary,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(14.dp))
                if (progress.totalCount > 0) {
                    val frac = progress.completedCount.toFloat() / progress.totalCount.toFloat()
                    LinearProgressIndicator(
                        progress = { frac },
                        modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(8.dp))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Dismiss")
            }
        }
    )
}

@Composable
private fun SwitchModeDialog(
    currentMode: OperatingMode,
    onSelectMode: (OperatingMode) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Text("Switch Operating Mode", fontWeight = FontWeight.Bold, fontSize = 17.sp)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ModeSelectionItem(
                    title = "Root Mode (SuperSU / Magisk / KernelSU)",
                    description = "Instant background termination via 'am force-stop' and kernel appops component cutoff. Requires root access.",
                    isSelected = currentMode == OperatingMode.ROOT,
                    onClick = { onSelectMode(OperatingMode.ROOT) }
                )
                ModeSelectionItem(
                    title = "Non-Root Mode (Accessibility Automation)",
                    description = "Safe automated force stop using Android Accessibility Service. Works on standard unrooted devices.",
                    isSelected = currentMode == OperatingMode.NON_ROOT,
                    onClick = { onSelectMode(OperatingMode.NON_ROOT) }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

@Composable
private fun ModeSelectionItem(
    title: String,
    description: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        ),
        border = if (isSelected) androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                if (isSelected) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = description,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 15.sp
            )
        }
    }
}
