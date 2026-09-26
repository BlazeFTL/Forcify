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
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
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
import androidx.compose.material3.FilterChip
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
import com.example.ui.theme.StateEvadingBg
import com.example.ui.theme.StateForeground
import com.example.ui.theme.StateFree
import com.example.ui.theme.StateFreeBg
import com.example.ui.theme.StateWorking
import com.example.ui.theme.StateWorkingBg
import com.example.ui.viewmodel.DashboardFilter
import com.example.ui.viewmodel.PureStopViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(viewModel: PureStopViewModel) {
    val operatingMode by viewModel.operatingMode.collectAsState()
    val allManagedApps by viewModel.allManagedApps.collectAsState()
    val pendingApps by viewModel.pendingApps.collectAsState()
    val hibernatedApps by viewModel.hibernatedApps.collectAsState()
    val allInstalledApps by viewModel.allInstalledApps.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val currentFilter by viewModel.currentFilter.collectAsState()
    val showAddAppsSheet by viewModel.showAddAppsSheet.collectAsState()
    val hideSystemAppsInAddList by viewModel.hideSystemAppsInAddList.collectAsState()
    val selectedAppForWakeup by viewModel.selectedAppForWakeup.collectAsState()
    val batchProgress by viewModel.batchProgress.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    var showMenu by remember { mutableStateOf(false) }
    var showModeDialog by remember { mutableStateOf(false) }
    var isSearchExpanded by remember { mutableStateOf(false) }
    var isHibernatedSectionExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(statusMessage) {
        statusMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearStatusMessage()
        }
    }

    val runningCount = pendingApps.size
    val evadingCount = remember(allManagedApps) {
        allManagedApps.count { it.state == AppState.EVADING_RESTRICTIONS }
    }
    val freeCount = hibernatedApps.size

    // Apply search filter
    val filteredPending = remember(pendingApps, searchQuery) {
        if (searchQuery.isBlank()) pendingApps else {
            pendingApps.filter {
                it.appName.contains(searchQuery, ignoreCase = true) ||
                it.packageName.contains(searchQuery, ignoreCase = true)
            }
        }
    }
    val filteredHibernated = remember(hibernatedApps, searchQuery) {
        if (searchQuery.isBlank()) hibernatedApps else {
            hibernatedApps.filter {
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
                            text = "PureStop",
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

                    // Cut All Wakeups Quick Action
                    IconButton(
                        onClick = { viewModel.cutAllActiveWakeups() },
                        modifier = Modifier.testTag("cut_wakeups_top_action")
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCut,
                            contentDescription = "Cut All Wakeups",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }

                    // Refresh Button
                    IconButton(onClick = { viewModel.refreshApps() }) {
                        Icon(imageVector = Icons.Default.Refresh, contentDescription = "Refresh Status")
                    }

                    // THE "+" BUTTON ON TOP REQUESTED BY USER!
                    IconButton(
                        onClick = { viewModel.openAddApps() },
                        modifier = Modifier
                            .testTag("add_apps_top_button")
                            .padding(end = 4.dp)
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

                    // Overflow Menu
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(imageVector = Icons.Default.MoreVert, contentDescription = "Options")
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
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
            // Optional Search Bar
            AnimatedVisibility(visible = isSearchExpanded) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { viewModel.setSearchQuery(it) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    placeholder = { Text("Filter managed apps...") },
                    leadingIcon = {
                        Icon(imageVector = Icons.Default.Search, contentDescription = null)
                    },
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface
                    )
                )
            }

            // Hero Overview Cards
            HeroStatsSection(
                totalManaged = allManagedApps.size,
                runningCount = runningCount,
                evadingCount = evadingCount,
                freeCount = freeCount,
                onFilterRunning = { viewModel.setFilter(DashboardFilter.PENDING) },
                onFilterEvading = { viewModel.setFilter(DashboardFilter.EVADING) },
                onFilterFree = { viewModel.setFilter(DashboardFilter.HIBERNATED) }
            )

            // Filter Tabs
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = currentFilter == DashboardFilter.PENDING,
                    onClick = { viewModel.setFilter(DashboardFilter.PENDING) },
                    label = { Text("Pending ($runningCount)", fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                )
                FilterChip(
                    selected = currentFilter == DashboardFilter.ALL,
                    onClick = { viewModel.setFilter(DashboardFilter.ALL) },
                    label = { Text("All (${allManagedApps.size})", fontSize = 12.sp) }
                )
                FilterChip(
                    selected = currentFilter == DashboardFilter.EVADING,
                    onClick = { viewModel.setFilter(DashboardFilter.EVADING) },
                    label = { Text("Evading ($evadingCount)", fontSize = 12.sp) }
                )
                FilterChip(
                    selected = currentFilter == DashboardFilter.HIBERNATED,
                    onClick = { viewModel.setFilter(DashboardFilter.HIBERNATED) },
                    label = { Text("Hibernated ($freeCount)", fontSize = 12.sp) }
                )
            }

            // Main App List
            if (isLoading && allManagedApps.isEmpty()) {
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else if (allManagedApps.isEmpty()) {
                EmptyStateView(
                    isSearchActive = searchQuery.isNotBlank(),
                    onAddApps = { viewModel.openAddApps() }
                )
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    when (currentFilter) {
                        DashboardFilter.PENDING -> {
                            // Section: Pending apps
                            if (filteredPending.isNotEmpty()) {
                                item {
                                    SectionHeader(
                                        title = "NOT HIBERNATING AUTOMATICALLY (${filteredPending.size})",
                                        subtitle = "Tap 'Stop' or use the FAB below to force stop"
                                    )
                                }
                                items(filteredPending, key = { it.packageName }) { app ->
                                    GreenifyStyleAppCard(
                                        app = app,
                                        onForceStop = { viewModel.forceStopSingle(app) },
                                        onOpenWakeup = { viewModel.selectAppForWakeup(app) },
                                        onRemove = { viewModel.removeAppFromFreezeList(app.packageName) }
                                    )
                                }
                            } else {
                                // All pending apps are stopped!
                                item {
                                    AllHibernatedCleanCard(
                                        freeCount = freeCount,
                                        onExpandHibernated = { isHibernatedSectionExpanded = true },
                                        onAddMore = { viewModel.openAddApps() }
                                    )
                                }
                            }

                            // Collapsible Hibernated section at bottom
                            if (filteredHibernated.isNotEmpty()) {
                                item {
                                    CollapsibleSectionHeader(
                                        title = "HIBERNATED / BACKGROUND FREE (${filteredHibernated.size})",
                                        isExpanded = isHibernatedSectionExpanded,
                                        onToggle = { isHibernatedSectionExpanded = !isHibernatedSectionExpanded }
                                    )
                                }
                                if (isHibernatedSectionExpanded) {
                                    items(filteredHibernated, key = { it.packageName }) { app ->
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

                        DashboardFilter.ALL -> {
                            if (filteredPending.isNotEmpty()) {
                                item {
                                    SectionHeader(title = "NOT HIBERNATING AUTOMATICALLY (${filteredPending.size})")
                                }
                                items(filteredPending, key = { it.packageName }) { app ->
                                    GreenifyStyleAppCard(
                                        app = app,
                                        onForceStop = { viewModel.forceStopSingle(app) },
                                        onOpenWakeup = { viewModel.selectAppForWakeup(app) },
                                        onRemove = { viewModel.removeAppFromFreezeList(app.packageName) }
                                    )
                                }
                            }
                            if (filteredHibernated.isNotEmpty()) {
                                item {
                                    Spacer(modifier = Modifier.height(10.dp))
                                    SectionHeader(title = "HIBERNATED (${filteredHibernated.size})")
                                }
                                items(filteredHibernated, key = { it.packageName }) { app ->
                                    GreenifyStyleAppCard(
                                        app = app,
                                        onForceStop = { viewModel.forceStopSingle(app) },
                                        onOpenWakeup = { viewModel.selectAppForWakeup(app) },
                                        onRemove = { viewModel.removeAppFromFreezeList(app.packageName) }
                                    )
                                }
                            }
                        }

                        DashboardFilter.EVADING -> {
                            val evadingList = allManagedApps.filter { it.state == AppState.EVADING_RESTRICTIONS }
                            if (evadingList.isEmpty()) {
                                item {
                                    Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                                        Text("No apps currently evading background restrictions.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            } else {
                                items(evadingList, key = { it.packageName }) { app ->
                                    GreenifyStyleAppCard(
                                        app = app,
                                        onForceStop = { viewModel.forceStopSingle(app) },
                                        onOpenWakeup = { viewModel.selectAppForWakeup(app) },
                                        onRemove = { viewModel.removeAppFromFreezeList(app.packageName) }
                                    )
                                }
                            }
                        }

                        DashboardFilter.HIBERNATED -> {
                            if (filteredHibernated.isEmpty()) {
                                item {
                                    Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                                        Text("No hibernated apps. Running apps need to be force stopped first.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            } else {
                                items(filteredHibernated, key = { it.packageName }) { app ->
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
            }
        }
    }

    // Modal Add Apps Sheet with Hide System Apps Toggle
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

    // Wake-Up Inspection & Cut Dialog
    selectedAppForWakeup?.let { app ->
        WakeUpCutDialog(
            app = app,
            operatingMode = operatingMode,
            onDismiss = { viewModel.selectAppForWakeup(null) },
            onCutWakeups = { viewModel.cutWakeups(it) },
            onRestoreWakeups = { viewModel.restoreWakeups(it) },
            onForceStop = { viewModel.forceStopSingle(it) }
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

@Composable
private fun SectionHeader(title: String, subtitle: String? = null) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp, horizontal = 4.dp)) {
        Text(
            text = title,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
            )
        }
    }
}

@Composable
private fun CollapsibleSectionHeader(
    title: String,
    isExpanded: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onToggle)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Icon(
            imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun HeroStatsSection(
    totalManaged: Int,
    runningCount: Int,
    evadingCount: Int,
    freeCount: Int,
    onFilterRunning: () -> Unit,
    onFilterEvading: () -> Unit,
    onFilterFree: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "App State Monitor",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "$totalManaged Managed",
                    fontWeight = FontWeight.Medium,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatMetricCard(
                    title = "Pending",
                    count = runningCount,
                    color = StateWorking,
                    bgColor = StateWorkingBg,
                    modifier = Modifier.weight(1f).clickable(onClick = onFilterRunning)
                )
                StatMetricCard(
                    title = "Evading",
                    count = evadingCount,
                    color = StateEvading,
                    bgColor = StateEvadingBg,
                    modifier = Modifier.weight(1f).clickable(onClick = onFilterEvading)
                )
                StatMetricCard(
                    title = "Free",
                    count = freeCount,
                    color = StateFree,
                    bgColor = StateFreeBg,
                    modifier = Modifier.weight(1f).clickable(onClick = onFilterFree)
                )
            }
        }
    }
}

@Composable
private fun StatMetricCard(
    title: String,
    count: Int,
    color: Color,
    bgColor: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(bgColor)
            .border(1.dp, color.copy(alpha = 0.25f), RoundedCornerShape(12.dp))
            .padding(vertical = 8.dp, horizontal = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "$count",
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold,
                color = color
            )
            Spacer(modifier = Modifier.height(1.dp))
            Text(
                text = title,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = color.copy(alpha = 0.85f)
            )
        }
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

    val isRunning = app.state != AppState.BACKGROUND_FREE && app.state != AppState.CACHED
    val stateText = when {
        app.stateDetail.isNotBlank() -> app.stateDetail
        app.state == AppState.EVADING_RESTRICTIONS -> "Running as foreground (evading restrictions)"
        app.state == AppState.FOREGROUND -> "Foreground (Ignored running state)"
        app.state == AppState.WORKING_STATE -> "Background service active"
        app.state == AppState.BACKGROUND_FREE -> "Hibernated"
        else -> app.state.label
    }

    val stateColor = when (app.state) {
        AppState.EVADING_RESTRICTIONS -> StateEvading
        AppState.FOREGROUND -> StateForeground
        AppState.WORKING_STATE -> StateWorking
        AppState.BACKGROUND_FREE -> StateFree
        AppState.CACHED -> Color(0xFF64748B)
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

            // Action button on right
            if (isRunning) {
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
            } else {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(StateFreeBg)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "Frozen",
                        fontSize = 11.sp,
                        color = StateFree,
                        fontWeight = FontWeight.SemiBold
                    )
                }
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
    freeCount: Int,
    onExpandHibernated: () -> Unit,
    onAddMore: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, StateFree.copy(alpha = 0.3f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(68.dp)
                    .clip(CircleShape)
                    .background(StateFreeBg),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = StateFree,
                    modifier = Modifier.size(40.dp)
                )
            }
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = "All Managed Apps Are Hibernated",
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "0 apps running in background. Your RAM and battery are protected.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                lineHeight = 17.sp
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = onExpandHibernated,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(imageVector = Icons.Default.AcUnit, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(text = "View Hibernated ($freeCount)", fontSize = 12.sp)
                }
                Button(
                    onClick = onAddMore,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(imageVector = Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(text = "Add Apps", fontSize = 12.sp)
                }
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
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = if (isSearchActive) "No Matching Apps Found" else "No Apps in Freeze List",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = if (isSearchActive)
                "Try searching by another keyword."
            else
                "Add apps to your list using the '+' button at the top to track working state and force stop them on demand.",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            lineHeight = 18.sp
        )
        if (!isSearchActive) {
            Spacer(modifier = Modifier.height(20.dp))
            Button(
                onClick = onAddApps,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(imageVector = Icons.Default.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(6.dp))
                Text(text = "Add Apps to Freeze List")
            }
        }
    }
}

@Composable
private fun BatchProgressDialog(
    progress: BatchFreezeProgress,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(12.dp))
                Text(text = "Freezing Applications...", fontSize = 17.sp, fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = progress.summary,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(12.dp))
                val fraction = if (progress.totalCount > 0)
                    progress.completedCount.toFloat() / progress.totalCount.toFloat()
                else 0f
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "${progress.completedCount} of ${progress.totalCount} completed",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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
        title = { Text(text = "Switch Operating Mode", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ModeOptionRow(
                    title = "Root Mode ⚡",
                    description = "Executes instant silent force stops and deep AppOps wake restrictions via su.",
                    isSelected = currentMode == OperatingMode.ROOT,
                    onClick = { onSelectMode(OperatingMode.ROOT) }
                )
                ModeOptionRow(
                    title = "Non-Root Mode 🛡️",
                    description = "Automates force stopping through Android Accessibility Service and standard power manager.",
                    isSelected = currentMode == OperatingMode.NON_ROOT,
                    onClick = { onSelectMode(OperatingMode.NON_ROOT) }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Done")
            }
        }
    )
}

@Composable
private fun ModeOptionRow(
    title: String,
    description: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = if (isSelected) 2.dp else 1.dp,
                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(10.dp)
            )
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f) else MaterialTheme.colorScheme.surface
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(text = title, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
            Spacer(modifier = Modifier.height(2.dp))
            Text(text = description, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
