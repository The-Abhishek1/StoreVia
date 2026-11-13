package com.xcloak.storevia.feature.onboarding

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xcloak.storevia.R
import com.xcloak.storevia.core.store.StoreType
import com.xcloak.storevia.core.store.StoreTypeConfig
import com.xcloak.storevia.data.prefs.AppPrefs
import com.xcloak.storevia.feature.settings.Field
import com.xcloak.storevia.feature.settings.StateDropdown
import com.xcloak.storevia.feature.settings.StoreSettingsViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel @Inject constructor(private val prefs: AppPrefs) : ViewModel() {
    /** Skipping counts as done: the Home screen then shows a gentle "finish setup" card instead of reopening this. */
    fun finish() = prefs.update { copy(onboardingDone = true) }
    fun setStoreType(type: StoreType) = prefs.update { copy(storeType = type) }
    val storeType get() = prefs.settings.value.storeType
}

internal fun shouldClearCloudRegistrationDetails(
    cloudConnectRequired: Boolean,
    clearRegistrationDetailsOnStart: Boolean,
    loaded: Boolean,
    step: Int,
): Boolean = cloudConnectRequired && clearRegistrationDetailsOnStart && loaded && step == 0

internal fun canSkipOnboarding(localSetupOnly: Boolean, cloudConnectRequired: Boolean): Boolean =
    !localSetupOnly && !cloudConnectRequired

/** Collects business details before the cloud credentials screen or local workspace creation. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OnboardingScreen(
    onFinish: () -> Unit,
    onImport: () -> Unit,
    onAddProduct: () -> Unit,
    cloudConnectRequired: Boolean = false,
    clearRegistrationDetailsOnStart: Boolean = false,
    localSetupOnly: Boolean = false,
    onCancel: () -> Unit = onFinish,
    store: StoreSettingsViewModel = hiltViewModel(),
    vm: OnboardingViewModel = hiltViewModel(),
) {
    val ui by store.ui.collectAsStateWithLifecycle()
    val f = ui.form
    val steps = if (cloudConnectRequired) 4 else 3
    var step by rememberSaveable { mutableIntStateOf(0) }
    var selectedType by rememberSaveable { mutableStateOf(vm.storeType) }
    var internetError by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val features = StoreTypeConfig.of(selectedType)

    LaunchedEffect(cloudConnectRequired, clearRegistrationDetailsOnStart, ui.loaded) {
        if (shouldClearCloudRegistrationDetails(
                cloudConnectRequired,
                clearRegistrationDetailsOnStart,
                ui.loaded,
                step,
            )
        ) {
            store.clearCloudRegistrationDetails()
        }
    }

    LaunchedEffect(cloudConnectRequired) {
        store.saved.collect {
            if (cloudConnectRequired) onFinish() else step = 2
        }
    }
    BackHandler {
        if (step > 0) step--
        else if (localSetupOnly || cloudConnectRequired) onCancel()
        else { vm.finish(); onFinish() }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            val displayedStep = step + 1
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.step_of, displayedStep, steps),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge,
                )
                if (canSkipOnboarding(localSetupOnly, cloudConnectRequired)) {
                    TextButton(onClick = { vm.finish(); onFinish() }) {
                        Text(stringResource(R.string.onboarding_skip))
                    }
                }
            }
            LinearProgressIndicator(
                progress = { if (cloudConnectRequired) displayedStep / 4f else displayedStep / steps.toFloat() },
                modifier = Modifier.fillMaxWidth(),
            )

            val stepIcons = if (cloudConnectRequired) {
                listOf(Icons.Filled.Storefront, Icons.Filled.Edit, Icons.Filled.Inventory2, Icons.Filled.Cloud)
            } else {
                listOf(Icons.Filled.Storefront, Icons.Filled.Edit, Icons.Filled.Inventory2)
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                stepIcons.forEachIndexed { index, icon ->
                    Surface(
                        modifier = Modifier
                            .size(42.dp)
                            .clickable(enabled = index < step) { step = index },
                        shape = MaterialTheme.shapes.large,
                        color = if (index <= step) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                    ) {
                        Box(contentAlignment = androidx.compose.ui.Alignment.Center) {
                            Icon(
                                icon,
                                contentDescription = stringResource(
                                    when (index) {
                                        0 -> R.string.onboarding_step_business_type
                                        1 -> R.string.onboarding_step_store_details
                                        2 -> R.string.onboarding_step_products
                                        else -> R.string.cloud_signup_step_credentials
                                    },
                                ),
                                tint = if (index <= step) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                }
            }

            androidx.compose.animation.AnimatedContent(
                targetState = step,
                label = "onboarding-step",
            ) { currentStep ->
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
            when (currentStep) {
                0 -> {
                    OnboardingHero(
                        icon = Icons.Filled.Storefront,
                        title = R.string.onboarding_welcome,
                        hint = R.string.onboarding_step1_body,
                    )
                    if (cloudConnectRequired && internetError) {
                        Text(
                            stringResource(R.string.cloud_signup_internet_required),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Text(stringResource(R.string.store_type_title), style = MaterialTheme.typography.titleMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        StoreType.entries.forEach { type ->
                            FilterChip(
                                selected = selectedType == type,
                                onClick = {
                                    selectedType = type
                                    vm.setStoreType(type)
                                },
                                label = { Text(stringResource(typeLabel(type))) },
                            )
                        }
                    }
                    val featureText = buildList {
                        if (features.supportsBatchTracking) add(stringResource(R.string.feature_batch_tracking))
                        if (features.supportsExpiryTracking) add(stringResource(R.string.feature_expiry_tracking))
                        if (features.supportsKhata) add(stringResource(R.string.feature_khata))
                        if (features.supportsSupplierLedger) add(stringResource(R.string.feature_supplier_balance))
                        if (features.supportsWholesale) add(stringResource(R.string.feature_wholesale))
                    }
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Filled.Storefront, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    stringResource(typeLabel(selectedType)),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                                Text(
                                    stringResource(R.string.onboarding_store_type_visual_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                                featureText.forEach { feature ->
                                    Text("• $feature", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                                }
                            }
                        }
                    }
                }
                1 -> {
                    OnboardingHero(
                        icon = Icons.Filled.Edit,
                        title = R.string.onboarding_step2_title,
                        hint = R.string.onboarding_step2_body,
                        detail = R.string.onboarding_store_details_visual_hint,
                    )
                    when {
                        ui.loading -> CircularProgressIndicator()
                        !ui.loaded -> {
                            Text(
                                stringResource(ui.loadError ?: R.string.err_store_unavailable),
                                color = MaterialTheme.colorScheme.error,
                            )
                            OutlinedButton(onClick = store::refresh) {
                                Text(stringResource(R.string.retry))
                            }
                        }
                        else -> {
                            Field(f.name, { v -> store.update { copy(name = v) } }, R.string.field_store_name, err(ui.errors["name"]))
                            Field(f.ownerName, { v -> store.update { copy(ownerName = v) } }, R.string.field_owner_name_required, err(ui.errors["ownerName"]))
                            Field(f.phone, { v -> store.update { copy(phone = v) } }, R.string.field_mobile_required, err(ui.errors["phone"]), keyboard = androidx.compose.ui.text.input.KeyboardType.Phone)
                            Field(f.address, { v -> store.update { copy(address = v) } }, R.string.field_address_required, err(ui.errors["address"]))
                            Field(f.city, { v -> store.update { copy(city = v) } }, R.string.field_city_required, err(ui.errors["city"]))
                            StateDropdown(f.stateCode, { code -> store.update { copy(stateCode = code) } }, err(ui.errors["stateCode"]))
                            Field(f.gstin, { v -> store.update { copy(gstin = v.uppercase()) } }, R.string.field_gstin, err(ui.errors["gstin"]), capitalize = true)
                            Text(stringResource(R.string.gstin_hint), style = MaterialTheme.typography.bodySmall)
                            ui.saveError?.let { error ->
                                Text(stringResource(error), color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
                else -> {
                    OnboardingHero(
                        icon = Icons.Filled.Inventory2,
                        title = R.string.onboarding_step3_title,
                        hint = R.string.onboarding_step3_body,
                        detail = R.string.onboarding_products_visual_hint,
                    )
                    if (localSetupOnly) {
                        Text(stringResource(R.string.entry_offline_setup_complete_hint), style = MaterialTheme.typography.bodyMedium)
                        Button(onClick = { vm.finish(); onFinish() }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                            Text(stringResource(R.string.entry_create_offline_store))
                        }
                    } else if (cloudConnectRequired) {
                        Button(onClick = { vm.finish(); onFinish() }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                            Text(stringResource(R.string.entry_continue_to_cloud))
                        }
                    } else {
                        Button(onClick = { vm.finish(); onImport() }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text(stringResource(R.string.onboarding_import)) }
                        OutlinedButton(onClick = { vm.finish(); onAddProduct() }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text(stringResource(R.string.onboarding_add_first)) }
                        TextButton(onClick = { vm.finish(); onFinish() }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.onboarding_later)) }
                    }
                }
                }
                }
            }
        }
        if (step < 2) {
            Button(
                onClick = {
                    if (step == 0) {
                        if (cloudConnectRequired && !hasValidatedInternet(context)) {
                            internetError = true
                        } else {
                            internetError = false
                            step = 1
                        }
                    }
                    else store.save(
                        requireRegistrationDetails = cloudConnectRequired || localSetupOnly,
                        setupFlow = cloudConnectRequired || localSetupOnly,
                    )
                },
                enabled = step == 0 || (ui.loaded && !ui.loading),
                modifier = Modifier.fillMaxWidth().padding(24.dp).height(52.dp),
            ) { Text(stringResource(R.string.next)) }
        }
        }
    }
}

@Composable
private fun OnboardingHero(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: Int,
    hint: Int,
    detail: Int? = null,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(18.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surface,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.padding(14.dp).size(32.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(title), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(stringResource(hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                detail?.let {
                    Text(stringResource(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        }
    }
}

@Composable
private fun typeLabel(type: StoreType): Int = when (type) {
    StoreType.GENERAL -> R.string.store_type_retail
    StoreType.MEDICAL -> R.string.store_type_medical
    StoreType.WHOLESALER -> R.string.store_type_wholesale
    StoreType.RESTAURANT -> R.string.store_type_restaurant
    StoreType.CLOTHING -> R.string.store_type_clothing
    StoreType.ELECTRONICS -> R.string.store_type_electronics
    StoreType.SERVICE -> R.string.store_type_service
    StoreType.OTHER -> R.string.store_type_other
}

@Composable
private fun err(res: Int?): String? = res?.let { stringResource(it) }

private fun hasValidatedInternet(context: Context): Boolean {
    val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val network = connectivityManager.activeNetwork ?: return false
    val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}
