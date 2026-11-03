package com.xcloak.storevia.core.ui

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.xcloak.storevia.core.access.Permission
import androidx.navigation.navArgument
import com.xcloak.storevia.R
import com.xcloak.storevia.feature.admin.PlatformAdminScreen
import com.xcloak.storevia.feature.customers.CustomerDetailScreen
import com.xcloak.storevia.feature.customers.CustomersScreen
import com.xcloak.storevia.feature.cloud.CloudAccountScreen
import com.xcloak.storevia.feature.data.BackupScreen
import com.xcloak.storevia.feature.data.ImportExportScreen
import com.xcloak.storevia.feature.home.HomeScreen
import com.xcloak.storevia.feature.more.MoreScreen
import com.xcloak.storevia.feature.purchases.PurchaseDetailScreen
import com.xcloak.storevia.feature.purchases.PurchaseEditScreen
import com.xcloak.storevia.feature.purchases.PurchasesListScreen
import com.xcloak.storevia.feature.purchases.ReorderScreen
import com.xcloak.storevia.feature.suppliers.SupplierDetailScreen
import com.xcloak.storevia.feature.suppliers.SuppliersScreen
import com.xcloak.storevia.feature.onboarding.OnboardingScreen
import com.xcloak.storevia.feature.reports.ReportsScreen
import com.xcloak.storevia.feature.settings.PreferencesScreen
import com.xcloak.storevia.feature.settings.StoreSettingsScreen
import com.xcloak.storevia.feature.sales.CheckoutScreen
import com.xcloak.storevia.feature.sales.ReturnScreen
import com.xcloak.storevia.feature.sales.SaleDetailScreen
import com.xcloak.storevia.feature.sales.SalesListScreen
import com.xcloak.storevia.feature.scanner.BarcodeScannerScreen
import com.xcloak.storevia.feature.staff.AuditScreen
import com.xcloak.storevia.feature.staff.StaffScreen
import com.xcloak.storevia.feature.stock.CategoriesScreen
import com.xcloak.storevia.feature.stock.ProductDetailScreen
import com.xcloak.storevia.feature.stock.ProductEditScreen
import com.xcloak.storevia.feature.stock.StockListScreen

private object Routes {
    const val HOME = "home"
    const val STOCK = "stock"
    const val SALES = "sales"
    const val REPORTS = "reports"
    const val MORE = "more"
    const val PRODUCT = "product/{id}"
    const val EDIT = "product_edit?id={id}&barcode={barcode}"
    const val SCANNER = "scanner"
    const val CHECKOUT = "checkout"
    const val SALE = "sale/{id}"
    const val RETURN = "sale/{id}/return"
    const val CUSTOMERS = "customers"
    const val STORE_SETTINGS = "store_settings"
    const val BACKUP = "backup"
    const val PREFS = "preferences"
    const val CATEGORIES = "categories"
    const val ONBOARDING = "onboarding"
    const val STAFF = "staff"
    const val AUDIT = "audit"
    const val SUPPLIERS = "suppliers"
    const val SUPPLIER = "supplier/{id}"
    const val PURCHASES = "purchases"
    const val PURCHASE_EDIT = "purchase_edit?supplierId={supplierId}"
    const val PURCHASE = "purchase/{id}"
    const val REORDER = "reorder"
    const val DATA = "import_export"
    const val PLATFORM_ADMIN = "platform_admin"
    const val CLOUD_ACCOUNT = "cloud_account"
    const val CUSTOMER = "customer/{id}"

    fun product(id: String) = "product/$id"
    fun sale(id: String) = "sale/$id"
    fun saleReturn(id: String) = "sale/$id/return"
    fun customer(id: String) = "customer/$id"
    fun supplier(id: String) = "supplier/$id"
    fun purchase(id: String) = "purchase/$id"
    fun purchaseEdit(supplierId: String? = null) = "purchase_edit" + (supplierId?.let { "?supplierId=${Uri.encode(it)}" } ?: "")
    fun edit(id: String? = null, barcode: String? = null): String {
        val parts = buildList {
            id?.let { add("id=${Uri.encode(it)}") }
            barcode?.let { add("barcode=${Uri.encode(it)}") }
        }
        return "product_edit" + if (parts.isEmpty()) "" else "?" + parts.joinToString("&")
    }
}

private enum class Tab(val route: String, val label: Int, val icon: ImageVector) {
    Home(Routes.HOME, R.string.nav_home, Icons.Filled.Home),
    Stock(Routes.STOCK, R.string.nav_stock, Icons.Filled.Inventory2),
    Sales(Routes.SALES, R.string.nav_sales, Icons.Filled.PointOfSale),
    Reports(Routes.REPORTS, R.string.nav_reports, Icons.Filled.BarChart),
    More(Routes.MORE, R.string.nav_more, Icons.Filled.MoreHoriz),
}

@Composable
fun StoreviaNavHost() {
    val nav = rememberNavController()
    val current by nav.currentBackStackEntryAsState()
    val route = current?.destination?.route
    val actor = LocalActor.current
    val tabs = Tab.entries.filter { it != Tab.Reports || actor.can(Permission.VIEW_REPORTS) }
    val showBottomBar = tabs.any { it.route == route }

    Scaffold(
        bottomBar = {
            if (showBottomBar) NavigationBar {
                tabs.forEach { tab ->
                    NavigationBarItem(
                        selected = route == tab.route,
                        onClick = {
                            nav.navigate(tab.route) {
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true; restoreState = true
                            }
                        },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(stringResource(tab.label)) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = Routes.HOME, modifier = Modifier.padding(padding).statusBarsPadding()) {
            composable(Routes.HOME) {
                HomeScreen(
                    onNewSale = { nav.navigate(Routes.CHECKOUT) },
                    onSetupStore = { nav.navigate(Routes.STORE_SETTINGS) },
                    onOnboarding = { nav.navigate(Routes.ONBOARDING) },
                )
            }

            composable(Routes.STOCK) {
                StockListScreen(
                    onAdd = { nav.navigate(Routes.edit()) },
                    onAddWithBarcode = { nav.navigate(Routes.edit(barcode = it)) },
                    onOpen = { nav.navigate(Routes.product(it)) },
                    onScan = { nav.navigate(Routes.SCANNER) },
                )
            }

            composable(Routes.PRODUCT, arguments = listOf(navArgument("id") { type = NavType.StringType })) {
                ProductDetailScreen(
                    onBack = { nav.popBackStack() },
                    onEdit = { nav.navigate(Routes.edit(id = it)) },
                )
            }

            composable(
                Routes.EDIT,
                arguments = listOf(
                    navArgument("id") { type = NavType.StringType; nullable = true; defaultValue = null },
                    navArgument("barcode") { type = NavType.StringType; nullable = true; defaultValue = null },
                ),
            ) {
                ProductEditScreen(
                    onBack = { nav.popBackStack() },
                    onScan = { nav.navigate(Routes.SCANNER) },
                    onSaved = { id ->
                        // New product -> open its detail; edited product -> return to it.
                        nav.navigate(Routes.product(id)) {
                            popUpTo(Routes.STOCK)
                            launchSingleTop = true
                        }
                    },
                    onArchived = { nav.popBackStack(Routes.STOCK, inclusive = false) },
                )
            }

            composable(Routes.SCANNER) {
                BarcodeScannerScreen(
                    onResult = { code ->
                        // Hand the result to the screen that opened the scanner, then close.
                        nav.previousBackStackEntry?.savedStateHandle?.set("barcode", code)
                        nav.popBackStack()
                    },
                    onBack = { nav.popBackStack() },
                )
            }

            composable(Routes.SALES) {
                SalesListScreen(onNewSale = { nav.navigate(Routes.CHECKOUT) }, onOpen = { nav.navigate(Routes.sale(it)) })
            }

            composable(Routes.CHECKOUT) {
                CheckoutScreen(
                    onBack = { nav.popBackStack() },
                    onScan = { nav.navigate(Routes.SCANNER) },
                    onCompleted = { id ->
                        nav.navigate(Routes.sale(id)) { popUpTo(Routes.CHECKOUT) { inclusive = true } }
                    },
                )
            }

            composable(Routes.SALE, arguments = listOf(navArgument("id") { type = NavType.StringType })) {
                SaleDetailScreen(onBack = { nav.popBackStack() }, onReturn = { nav.navigate(Routes.saleReturn(it)) })
            }

            composable(Routes.RETURN, arguments = listOf(navArgument("id") { type = NavType.StringType })) {
                ReturnScreen(onBack = { nav.popBackStack() }, onDone = { nav.popBackStack() })
            }

            composable(Routes.MORE) {
                MoreScreen(
                    onCustomers = { nav.navigate(Routes.CUSTOMERS) },
                    onStoreSettings = { nav.navigate(Routes.STORE_SETTINGS) },
                    onBackup = { nav.navigate(Routes.BACKUP) },
                    onData = { nav.navigate(Routes.DATA) },
                    onPrefs = { nav.navigate(Routes.PREFS) },
                    onCategories = { nav.navigate(Routes.CATEGORIES) },
                    onSuppliers = { nav.navigate(Routes.SUPPLIERS) },
                    onPurchases = { nav.navigate(Routes.PURCHASES) },
                    onReorder = { nav.navigate(Routes.REORDER) },
                    onStaff = { nav.navigate(Routes.STAFF) },
                    onAudit = { nav.navigate(Routes.AUDIT) },
                    onCloudAccount = { nav.navigate(Routes.CLOUD_ACCOUNT) },
                )
            }

            composable(Routes.SUPPLIERS) { SuppliersScreen(onBack = { nav.popBackStack() }, onOpen = { nav.navigate(Routes.supplier(it)) }) }
            composable(Routes.SUPPLIER, arguments = listOf(navArgument("id") { type = NavType.StringType })) {
                SupplierDetailScreen(
                    onBack = { nav.popBackStack() },
                    onNewPurchase = { nav.navigate(Routes.purchaseEdit(it)) },
                    onOpenPurchase = { nav.navigate(Routes.purchase(it)) },
                )
            }
            composable(Routes.PURCHASES) {
                PurchasesListScreen(onBack = { nav.popBackStack() }, onNew = { nav.navigate(Routes.purchaseEdit()) }, onOpen = { nav.navigate(Routes.purchase(it)) })
            }
            composable(
                Routes.PURCHASE_EDIT,
                arguments = listOf(navArgument("supplierId") { type = NavType.StringType; nullable = true; defaultValue = null }),
            ) {
                PurchaseEditScreen(
                    onBack = { nav.popBackStack() },
                    onScan = { nav.navigate(Routes.SCANNER) },
                    onSaved = { id -> nav.navigate(Routes.purchase(id)) { popUpTo(Routes.PURCHASE_EDIT) { inclusive = true } } },
                )
            }
            composable(Routes.PURCHASE, arguments = listOf(navArgument("id") { type = NavType.StringType })) {
                PurchaseDetailScreen(onBack = { nav.popBackStack() })
            }
            composable(Routes.REORDER) { ReorderScreen(onBack = { nav.popBackStack() }, onNewPurchase = { nav.navigate(Routes.purchaseEdit()) }) }
            composable(Routes.STAFF) { StaffScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.AUDIT) { AuditScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.CATEGORIES) { CategoriesScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.ONBOARDING) {
                OnboardingScreen(
                    onFinish = { nav.popBackStack() },
                    onImport = { nav.popBackStack(); nav.navigate(Routes.DATA) },
                    onAddProduct = { nav.popBackStack(); nav.navigate(Routes.edit()) },
                )
            }
            composable(Routes.PREFS) { PreferencesScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.BACKUP) { BackupScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.DATA) { ImportExportScreen(onBack = { nav.popBackStack() }) }

            composable(Routes.STORE_SETTINGS) { StoreSettingsScreen(onBack = { nav.popBackStack() }) }

            composable(Routes.PLATFORM_ADMIN) { PlatformAdminScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.CLOUD_ACCOUNT) {
                CloudAccountScreen(
                    onBack = { nav.popBackStack() },
                    onPlatformAdmin = { nav.navigate(Routes.PLATFORM_ADMIN) },
                )
            }

            composable(Routes.CUSTOMERS) {
                CustomersScreen(onBack = { nav.popBackStack() }, onOpen = { nav.navigate(Routes.customer(it)) })
            }

            composable(Routes.CUSTOMER, arguments = listOf(navArgument("id") { type = NavType.StringType })) {
                CustomerDetailScreen(onBack = { nav.popBackStack() }, onOpenSale = { nav.navigate(Routes.sale(it)) })
            }

            composable(Routes.REPORTS) { ReportsScreen() }
        }
    }
}

