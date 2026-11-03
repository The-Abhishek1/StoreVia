package com.xcloak.storevia.feature.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
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

private const val STEPS = 3

/** Three short steps: shop name and state, GSTIN, then how to get products in. Reuses the store-settings validation. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OnboardingScreen(
    onFinish: () -> Unit,
    onImport: () -> Unit,
    onAddProduct: () -> Unit,
    cloudConnectRequired: Boolean = false,
    store: StoreSettingsViewModel = hiltViewModel(),
    vm: OnboardingViewModel = hiltViewModel(),
) {
    val ui by store.ui.collectAsStateWithLifecycle()
    val f = ui.form
    var step by rememberSaveable { mutableIntStateOf(0) }
    var selectedType by rememberSaveable { mutableStateOf(vm.storeType) }
    val features = StoreTypeConfig.of(selectedType)

    LaunchedEffect(Unit) { store.saved.collect { step = 2 } }
    BackHandler { if (step > 0) step-- else { vm.finish(); onFinish() } }

    Column(Modifier.fillMaxSize().imePadding()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.step_of, step + 1, STEPS), style = MaterialTheme.typography.labelLarge)
            LinearProgressIndicator(progress = { (step + 1) / STEPS.toFloat() }, modifier = Modifier.fillMaxWidth())

            when (step) {
                0 -> {
                    Text(stringResource(R.string.onboarding_welcome), style = MaterialTheme.typography.headlineMedium)
                    Text(stringResource(R.string.onboarding_step1_body), style = MaterialTheme.typography.bodyLarge)
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
                    if (featureText.isNotEmpty()) {
                        Text(stringResource(R.string.store_type_features), style = MaterialTheme.typography.labelLarge)
                        featureText.forEach { feature -> Text("• $feature", style = MaterialTheme.typography.bodyMedium) }
                    }
                    Field(f.name, { v -> store.update { copy(name = v) } }, R.string.field_store_name, err(ui.errors["name"]))
                    StateDropdown(f.stateCode) { code -> store.update { copy(stateCode = code) } }
                }
                1 -> {
                    Text(stringResource(R.string.onboarding_step2_title), style = MaterialTheme.typography.headlineSmall)
                    Text(stringResource(R.string.onboarding_step2_body), style = MaterialTheme.typography.bodyLarge)
                    Field(f.gstin, { v -> store.update { copy(gstin = v.uppercase()) } }, R.string.field_gstin, err(ui.errors["gstin"]), capitalize = true)
                    Text(stringResource(R.string.gstin_hint), style = MaterialTheme.typography.bodySmall)
                }
                else -> {
                    Text(stringResource(R.string.onboarding_step3_title), style = MaterialTheme.typography.headlineSmall)
                    Text(stringResource(R.string.onboarding_step3_body), style = MaterialTheme.typography.bodyLarge)
                    if (cloudConnectRequired) {
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
        if (step < 2) {
            Button(
                onClick = {
                    if (step == 0) { if (f.name.isNotBlank()) step = 1 else store.save() }   // blank name -> shows the name error
                    else store.save()                                                          // validates GSTIN, then moves to step 3
                },
                modifier = Modifier.fillMaxWidth().padding(24.dp).height(52.dp),
            ) { Text(stringResource(R.string.next)) }
        }
    }
}

@Composable
private fun typeLabel(type: StoreType): Int = when (type) {
    StoreType.GENERAL -> R.string.store_type_general
    StoreType.MEDICAL -> R.string.store_type_medical
    StoreType.WHOLESALER -> R.string.store_type_wholesale
}

@Composable
private fun err(res: Int?): String? = res?.let { stringResource(it) }
