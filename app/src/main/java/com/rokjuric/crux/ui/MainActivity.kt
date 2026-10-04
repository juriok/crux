package com.rokjuric.crux.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.rokjuric.crux.CruxApp
import com.rokjuric.crux.data.LogStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()
    /** Session id to jump into, set when the workout notification is tapped. */
    private val openWorkout = MutableStateFlow<String?>(null)

    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handle(intent)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            CruxTheme { CruxNav(vm, openWorkout) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onStart() {
        super.onStart()
        val repo = (application as CruxApp).repo
        if (repo.settings.token.isNotBlank()) lifecycleScope.launch { repo.refresh() }
    }

    private fun handle(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_WORKOUT, false) != true) return
        intent.removeExtra(EXTRA_OPEN_WORKOUT)
        lifecycleScope.launch {
            val active = vm.repo.logs.first().firstOrNull { it.status == LogStatus.IN_PROGRESS }
            openWorkout.value = active?.sessionId
        }
    }

    companion object {
        const val EXTRA_OPEN_WORKOUT = "open_workout"
    }
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("today", "Today", Icons.Filled.Today),
    Tab("plan", "Plan", Icons.Filled.CalendarMonth),
    Tab("history", "History", Icons.Filled.History),
    Tab("settings", "Settings", Icons.Filled.Settings),
)

@Composable
private fun CruxNav(vm: MainViewModel, openWorkout: MutableStateFlow<String?>) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val pending by openWorkout.collectAsStateWithLifecycle()

    LaunchedEffect(pending) {
        pending?.let {
            nav.navigate("workout/$it") { launchSingleTop = true }
            openWorkout.value = null
        }
    }

    Scaffold(
        bottomBar = {
            if (tabs.any { it.route == route }) {
                NavigationBar {
                    tabs.forEach { t ->
                        NavigationBarItem(
                            selected = route == t.route,
                            onClick = { nav.switchTab(t.route) },
                            icon = { Icon(t.icon, null) },
                            label = { Text(t.label) },
                        )
                    }
                }
            }
        },
    ) { inner ->
        NavHost(
            navController = nav,
            startDestination = "today",
            modifier = Modifier.padding(inner).consumeWindowInsets(inner),
        ) {
            composable("today") {
                TodayScreen(
                    vm,
                    onOpenSession = { nav.navigate("session/$it") },
                    onStartWorkout = { nav.navigate("workout/$it") { launchSingleTop = true } },
                    onOpenLog = { nav.navigate("log/$it") },
                    onOpenSettings = { nav.switchTab("settings") },
                )
            }
            composable("plan") { PlanScreen(vm, onOpenSession = { nav.navigate("session/$it") }) }
            composable("history") { HistoryScreen(vm, onOpenLog = { nav.navigate("log/$it") }) }
            composable("settings") { SettingsScreen(vm) }
            composable("session/{id}") { e ->
                SessionScreen(
                    vm,
                    sessionId = e.arguments?.getString("id").orEmpty(),
                    onBack = { nav.popBackStack() },
                    onStartWorkout = { nav.navigate("workout/$it") { launchSingleTop = true } },
                    onOpenLog = { nav.navigate("log/$it") },
                )
            }
            composable("workout/{sessionId}") {
                WorkoutScreen(onClose = { if (!nav.popBackStack()) nav.navigate("today") })
            }
            composable("log/{id}") { e ->
                LogScreen(vm, logId = e.arguments?.getString("id").orEmpty(), onBack = { nav.popBackStack() })
            }
        }
    }
}

private fun NavHostController.switchTab(route: String) = navigate(route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}
