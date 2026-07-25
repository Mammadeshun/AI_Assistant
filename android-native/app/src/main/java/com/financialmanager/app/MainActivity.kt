package com.financialmanager.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.EventRepeat
import androidx.compose.material.icons.outlined.PieChartOutline
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.financialmanager.app.ui.AppViewModel
import com.financialmanager.app.ui.AssistantScreen
import com.financialmanager.app.ui.DashboardScreen
import com.financialmanager.app.ui.FinancialManagerTheme
import com.financialmanager.app.ui.ImportState
import com.financialmanager.app.ui.PlanScreen
import com.financialmanager.app.ui.SettingsScreen
import com.financialmanager.app.ui.TransactionsScreen
import kotlin.math.roundToInt

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
    Dashboard("Home", Icons.Outlined.PieChartOutline),
    Transactions("Activity", Icons.Outlined.CreditCard),
    Plan("Plan", Icons.Outlined.EventRepeat),
    Settings("Settings", Icons.Outlined.Tune),
}

@Composable
private fun App(model: AppViewModel = viewModel()) {
    var tab by remember { mutableStateOf(Tab.Dashboard) }
    var assistantOpen by rememberSaveable { mutableStateOf(false) }
    val snackbars = remember { SnackbarHostState() }
    val importState by model.importState.collectAsStateWithLifecycle()

    // Opens the system file picker. Statements arrive from all sorts of places —
    // Downloads, Drive, an email attachment — so anything is accepted and the
    // format is worked out from the file's own bytes.
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(model::importStatement) }

    // Everything, rather than a list of MIME types. Some file providers hide
    // files whose declared type does not match the filter, and the format is
    // worked out from the file's own bytes anyway.
    fun pickStatement() = picker.launch(arrayOf("*/*"))

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
        // No title bar. Each screen sets its own large title where the content
        // starts, which leaves the top of a small phone to the content.
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                Tab.entries.forEach { entry ->
                    NavigationBarItem(
                        selected = tab == entry,
                        onClick = { tab = entry },
                        icon = { Icon(entry.icon, contentDescription = entry.label) },
                        label = { Text(entry.label) },
                        colors = NavigationBarItemDefaults.colors(
                            indicatorColor = MaterialTheme.colorScheme.surfaceVariant,
                        ),
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize()) {
            when (tab) {
                Tab.Dashboard -> DashboardScreen(model, padding, ::pickStatement) { tab = Tab.Plan }
                Tab.Transactions -> TransactionsScreen(model, padding)
                Tab.Plan -> PlanScreen(model, padding)
                Tab.Settings -> SettingsScreen(model, padding, ::pickStatement)
            }

            // The assistant used to be a fifth tab, which put a permanent stripe
            // of chrome across the bottom of a phone for something used a few
            // times a day. It is a button you can move instead — out of the way
            // of whatever row it happens to be covering.
            if (!assistantOpen) {
                AssistantBubble(bottomPadding = padding.calculateBottomPadding()) {
                    assistantOpen = true
                }
            }
        }
    }

    AnimatedVisibility(
        visible = assistantOpen,
        enter = slideInVertically { it / 4 } + fadeIn(),
        exit = slideOutVertically { it / 4 } + fadeOut(),
    ) {
        AssistantSheet(
            model = model,
            onClose = { assistantOpen = false },
            onOpenSettings = { assistantOpen = false; tab = Tab.Settings },
        )
    }
}

/**
 * The assistant, over the top of whatever screen you were on.
 *
 * Not a tab and not a dialog: you ask something, see the answer against the
 * screen you were reading, and dismiss it.
 */
@Composable
private fun AssistantSheet(
    model: AppViewModel,
    onClose: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 8.dp, top = 44.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Assistant",
                        style = MaterialTheme.typography.headlineLarge,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onClose) {
                        Icon(Icons.Outlined.Close, contentDescription = "Close")
                    }
                }
            },
        ) { padding ->
            AssistantScreen(model, padding, onOpenSettings)
        }
    }
}

/**
 * A button that can be dragged anywhere on the screen.
 *
 * Its position is remembered across rotations and while moving between tabs, so
 * once it is somewhere that suits your thumb it stays there. It starts bottom
 * right, above the tab bar, and is clamped inside the screen so it can never be
 * flung somewhere it cannot be retrieved from.
 */
@Composable
private fun BoxScope.AssistantBubble(
    bottomPadding: androidx.compose.ui.unit.Dp,
    onOpen: () -> Unit,
) {
    val density = LocalDensity.current
    val size = 58.dp
    val margin = 18.dp

    var bounds by remember { mutableStateOf(Offset.Zero) }
    // NaN until it has been placed, because where "bottom right" is depends on a
    // screen size that is not known on the first composition.
    var x by rememberSaveable { mutableFloatStateOf(Float.NaN) }
    var y by rememberSaveable { mutableFloatStateOf(Float.NaN) }

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { measured ->
                bounds = Offset(measured.width.toFloat(), measured.height.toFloat())
                if (x.isNaN() || y.isNaN()) {
                    with(density) {
                        x = measured.width - (size + margin).toPx()
                        y = measured.height - (size + margin).toPx() - bottomPadding.toPx()
                    }
                }
            }
    ) {
        if (x.isNaN() || y.isNaN()) return@Box

        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            shadowElevation = 8.dp,
            modifier = Modifier
                .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
                .size(size)
                .pointerInput(bounds) {
                    detectDragGestures { change, delta ->
                        change.consume()
                        val limit = with(density) { size.toPx() }
                        x = (x + delta.x).coerceIn(0f, (bounds.x - limit).coerceAtLeast(0f))
                        y = (y + delta.y).coerceIn(0f, (bounds.y - limit).coerceAtLeast(0f))
                    }
                }
                .clickable(onClick = onOpen),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Outlined.AutoAwesome,
                    contentDescription = "Ask the assistant",
                    modifier = Modifier.size(25.dp),
                )
            }
        }
    }
}
