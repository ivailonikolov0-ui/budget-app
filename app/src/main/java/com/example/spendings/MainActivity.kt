package com.example.spendings

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import com.example.spendings.ui.AddScreen
import com.example.spendings.ui.AnalysisScreen
import com.example.spendings.ui.MonthScreen
import com.example.spendings.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AppTheme { App() } }
    }
}

private data class Tab(val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("Add", Icons.Filled.Add),
    Tab("Month", Icons.AutoMirrored.Filled.List),
    Tab("Analysis", Icons.Filled.Info),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(vm: BudgetViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    // Open Settings automatically the first time, until the sheet is connected.
    var showSettings by rememberSaveable { mutableStateOf(!state.settings.isConfigured) }
    val snackbar = remember { SnackbarHostState() }

    val scope = rememberCoroutineScope()
    LaunchedEffect(state.message) {
        val msg = state.message ?: return@LaunchedEffect
        vm.messageShown()
        snackbar.currentSnackbarData?.dismiss()
        scope.launch { snackbar.showSnackbar(msg) }
    }
    BackHandler(enabled = showSettings) { showSettings = false }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(if (showSettings) "Settings" else tabs[tab].label)
                },
                navigationIcon = {
                    if (showSettings) IconButton(onClick = { showSettings = false }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    if (!showSettings) {
                        if (state.problem != null && !state.loading) {
                            IconButton(onClick = { showSettings = true }) {
                                Icon(Icons.Filled.Warning, "Connection problem", tint = MaterialTheme.colorScheme.error)
                            }
                        }
                        if (state.pending.isNotEmpty()) {
                            Text(
                                "${state.pending.size} not sent",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        if (state.loading) {
                            Box(Modifier.padding(12.dp)) { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp) }
                        } else {
                            IconButton(onClick = { vm.refresh(fresh = true) }) { Icon(Icons.Filled.Refresh, "Refresh from the sheet") }
                        }
                        IconButton(onClick = { showSettings = true }) { Icon(Icons.Filled.Settings, "Settings") }
                    }
                },
            )
        },
        bottomBar = {
            if (!showSettings) NavigationBar {
                tabs.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = { Icon(t.icon, null) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when {
                showSettings -> SettingsScreen(
                    state.settings, state.problem, state.testing, state.testLines,
                    onTest = vm::testConnection,
                    onSave = {
                    vm.saveSettings(it)
                    showSettings = false
                })
                tab == 0 -> AddScreen(state.current, onExpense = vm::addExpense, onIncome = vm::addIncome)
                tab == 1 -> MonthScreen(state.viewed, state.pending, onSelectMonth = vm::selectMonth, onEdit = vm::edit)
                else -> AnalysisScreen(state.viewed, onSelectMonth = vm::selectMonth)
            }
        }
    }
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme(primary = Color(0xFF7FD3BC))
        else -> lightColorScheme(primary = Color(0xFF1F6F5C))
    }
    MaterialTheme(colorScheme = colors, content = content)
}
