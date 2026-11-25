package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.outlined.Assessment
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.rounded.Assessment
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.ai.configureAppCheck
import com.example.data.FirestoreColdStorageRepository
import com.example.domain.invoice.InvoiceAiExtractor
import com.example.domain.invoice.InvoiceLocalExtractor
import com.example.ui.auth.authStateFlow
import com.example.ui.auth.signOutUser
import com.example.ui.components.TeamAccessSheet
import com.example.ui.screens.AuthScreen
import com.example.ui.screens.BarcodeScannerScreen
import com.example.ui.screens.DashboardScreen
import com.example.ui.screens.InventoryScreen
import com.example.ui.screens.ReceivingScreen
import com.example.ui.screens.ReportsScreen
import com.example.ui.theme.FrigStockTheme
import com.example.ui.viewmodel.DashboardViewModel
import com.example.ui.viewmodel.FrigStockViewModelFactory
import com.example.ui.viewmodel.InventoryViewModel
import com.example.ui.viewmodel.ReceivingViewModel
import com.example.ui.viewmodel.ReportsViewModel
import com.example.ui.viewmodel.TeamViewModel
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.serialization.Serializable

sealed interface AppRoute {
    @Serializable
    data object Dashboard : AppRoute

    @Serializable
    data object Receiving : AppRoute

    @Serializable
    data object Inventory : AppRoute

    @Serializable
    data object Reports : AppRoute

    @Serializable
    data object BarcodeScanner : AppRoute
}

private data class TopDestination(
    val route: AppRoute,
    val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String
)

private val topDestinations = listOf(
    TopDestination(
        route = AppRoute.Dashboard,
        labelRes = R.string.nav_dashboard,
        selectedIcon = Icons.Rounded.Dashboard,
        unselectedIcon = Icons.Outlined.Dashboard,
        testTag = "nav_dashboard"
    ),
    TopDestination(
        route = AppRoute.Receiving,
        labelRes = R.string.nav_receiving,
        selectedIcon = Icons.AutoMirrored.Rounded.ReceiptLong,
        unselectedIcon = Icons.AutoMirrored.Outlined.ReceiptLong,
        testTag = "nav_receiving"
    ),
    TopDestination(
        route = AppRoute.Inventory,
        labelRes = R.string.nav_inventory,
        selectedIcon = Icons.Rounded.Inventory2,
        unselectedIcon = Icons.Outlined.Inventory2,
        testTag = "nav_inventory"
    ),
    TopDestination(
        route = AppRoute.Reports,
        labelRes = R.string.nav_reports,
        selectedIcon = Icons.Rounded.Assessment,
        unselectedIcon = Icons.Outlined.Assessment,
        testTag = "nav_reports"
    )
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Mandatory Step 1: Configure App Check with launch intent before UI or ViewModels
        configureAppCheck(this, intent)

        enableEdgeToEdge()

        setContent {
            FrigStockTheme {
                AppNavigation()
            }
        }
    }
}

@Composable
private fun AppNavigation(auth: FirebaseAuth = Firebase.auth) {
    val currentUser by auth.authStateFlow().collectAsStateWithLifecycle(initialValue = auth.currentUser)
    val user = currentUser

    if (user == null) {
        AuthScreen(onAuthSuccess = {})
    } else {
        key(user.uid) {
            AuthenticatedFrigStockShell(
                currentUserId = user.uid,
                userEmail = user.email.orEmpty(),
                userDisplayName = user.displayName.orEmpty()
            )
        }
    }
}

@Composable
private fun AuthenticatedFrigStockShell(
    currentUserId: String,
    userEmail: String,
    userDisplayName: String
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentialManager = remember(context) { CredentialManager.create(context) }

    val repository = remember(currentUserId) {
        val databaseId = context.applicationContext.getString(R.string.firestore_database_id)
        val db = FirebaseFirestore.getInstance(databaseId)
        FirestoreColdStorageRepository(
            db = db,
            currentUserId = currentUserId,
            currentUserEmail = userEmail,
            currentUserDisplayName = userDisplayName
        )
    }

    val factory = remember(repository) {
        FrigStockViewModelFactory(
            repository = repository,
            localExtractor = InvoiceLocalExtractor(),
            aiExtractor = InvoiceAiExtractor()
        )
    }

    LaunchedEffect(currentUserId) {
        repository.ensureSessionReady()
    }

    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val showNavigationChrome = currentDestination?.hasRoute<AppRoute.BarcodeScanner>() != true

    val dashboardViewModel: DashboardViewModel = viewModel(key = "dash_$currentUserId", factory = factory)
    val receivingViewModel: ReceivingViewModel = viewModel(key = "recv_$currentUserId", factory = factory)
    val inventoryViewModel: InventoryViewModel = viewModel(key = "inv_$currentUserId", factory = factory)
    val reportsViewModel: ReportsViewModel = viewModel(key = "rep_$currentUserId", factory = factory)
    val teamViewModel: TeamViewModel = viewModel(key = "team_$currentUserId", factory = factory)

    var showTeamSheet by remember { mutableStateOf(false) }

    val navigateTopLevel = remember(navController) {
        { route: AppRoute ->
            navController.navigate(route) {
                popUpTo(navController.graph.findStartDestination().id) {
                    saveState = true
                }
                launchSingleTop = true
                restoreState = true
            }
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useRail = maxWidth >= 600.dp

        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            bottomBar = {
                if (showNavigationChrome && !useRail) {
                    NavigationBar(windowInsets = WindowInsets.navigationBars) {
                        topDestinations.forEach { dest ->
                            val selected = currentDestination?.hasRoute(dest.route::class) == true
                            val label = stringResource(dest.labelRes)
                            NavigationBarItem(
                                selected = selected,
                                onClick = { navigateTopLevel(dest.route) },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                        contentDescription = label
                                    )
                                },
                                label = { Text(label) },
                                modifier = Modifier.testTag(dest.testTag)
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (showNavigationChrome && useRail) {
                    NavigationRail(modifier = Modifier.fillMaxHeight()) {
                        topDestinations.forEach { dest ->
                            val selected = currentDestination?.hasRoute(dest.route::class) == true
                            val label = stringResource(dest.labelRes)
                            NavigationRailItem(
                                selected = selected,
                                onClick = { navigateTopLevel(dest.route) },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                        contentDescription = label
                                    )
                                },
                                label = { Text(label) },
                                modifier = Modifier.testTag(dest.testTag)
                            )
                        }
                    }
                }

                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.TopCenter
                ) {
                    NavHost(
                        navController = navController,
                        startDestination = AppRoute.Dashboard,
                        modifier = Modifier
                            .fillMaxSize()
                            .widthIn(max = 920.dp)
                    ) {
                        composable<AppRoute.Dashboard> {
                            DashboardScreen(
                                viewModel = dashboardViewModel,
                                onNavigateToReceiving = { navigateTopLevel(AppRoute.Receiving) },
                                onNavigateToInventory = { urgencyFilter ->
                                    inventoryViewModel.setUrgencyFilter(urgencyFilter)
                                    navigateTopLevel(AppRoute.Inventory)
                                },
                                onOpenTeamAccess = { showTeamSheet = true }
                            )
                        }
                        composable<AppRoute.Receiving> {
                            ReceivingScreen(
                                viewModel = receivingViewModel,
                                onOpenBarcodeScanner = { navController.navigate(AppRoute.BarcodeScanner) }
                            )
                        }
                        composable<AppRoute.Inventory> {
                            InventoryScreen(viewModel = inventoryViewModel)
                        }
                        composable<AppRoute.Reports> {
                            ReportsScreen(viewModel = reportsViewModel)
                        }
                        composable<AppRoute.BarcodeScanner> {
                            BarcodeScannerScreen(
                                onCodeDetected = { rawCode ->
                                    receivingViewModel.applyScannedBarcode(rawCode)
                                    navController.popBackStack()
                                },
                                onNavigateBack = { navController.popBackStack() }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showTeamSheet) {
        TeamAccessSheet(
            viewModel = teamViewModel,
            userEmail = userEmail,
            userDisplayName = userDisplayName,
            onDismiss = { showTeamSheet = false },
            onSignOutClick = {
                showTeamSheet = false
                signOutUser(credentialManager, scope)
            }
        )
    }
}
