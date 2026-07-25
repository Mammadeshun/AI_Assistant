package com.financialmanager.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.financialmanager.app.ui.AppViewModel
import com.financialmanager.app.ui.AssistantScreen
import com.financialmanager.app.ui.DashboardScreen
import com.financialmanager.app.ui.FinancialManagerTheme
import com.financialmanager.app.ui.ImportState
import com.financialmanager.app.ui.SettingsScreen
import com.financialmanager.app.ui.TransactionsScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FinancialManagerTheme {
                App()
            }
        }
    }
}

private enum class Tab(val label: String, val icon: ImageVector) {
    Dashboard("Home", Icons.Default.PieChart),
    Transactions("Activity", Icons.Default.ReceiptLong),
    Assistant("Ask", Icons.Default.AutoAwesome),
    Settings("Settings", Icons.Default.Settings),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun App(model: AppViewModel = viewModel()) {
    var tab by remember { mutableStateOf(Tab.Dashboard) }
    val snackbars = remember { SnackbarHostState() }
    val importState by model.importState.collectAsStateWithLifecycle()

    // Opens the system file picker. Statements arrive from all sorts of places —
    // Downloads, Drive, an email attachment — so anything is accepted and the
    // format is worked out from the file's own bytes.
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(model::importStatement) }

    fun pickStatement() = picker.launch(arrayOf("application/pdf", "text/csv", "text/comma-separated-values", "*/*"))

    LaunchedEffect(importState) {
        when (val state = importState) {
            is ImportState.Done -> {
                val result = state.result
                val message = buildString {
                    append("Imported ${result.added} of ${result.parsed} rows")
                    if (result.datesEstimated > 0) {
                        append(" · ${result.datesEstimated} dates taken from the row above")
                    }
                }
                snackbars.showSnackbar(message)
                model.clearImportState()
            }
            is ImportState.Failed -> {
                snackbars.showSnackbar(state.message)
                model.clearImportState()
            }
            else -> Unit
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbars) },
        topBar = {
            TopAppBar(title = { Text(if (tab == Tab.Dashboard) "Finances" else tab.label) })
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { entry ->
                    NavigationBarItem(
                        selected = tab == entry,
                        onClick = { tab = entry },
                        icon = { Icon(entry.icon, contentDescription = entry.label) },
                        label = { Text(entry.label) },
                    )
                }
            }
        },
    ) { padding ->
        when (tab) {
            Tab.Dashboard -> DashboardScreen(model, padding, ::pickStatement)
            Tab.Transactions -> TransactionsScreen(model, padding)
            Tab.Assistant -> AssistantScreen(model, padding) { tab = Tab.Settings }
            Tab.Settings -> SettingsScreen(model, padding, ::pickStatement)
        }
    }
}
