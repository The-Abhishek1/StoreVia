package com.xcloak.storevia.feature.staff

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xcloak.storevia.R
import com.xcloak.storevia.core.access.Role
import com.xcloak.storevia.core.database.entity.StaffEntity
import com.xcloak.storevia.core.ui.LoadingState
import com.xcloak.storevia.data.staff.PinPolicy
import kotlinx.coroutines.launch

@Composable
fun roleLabel(role: Role): String = stringResource(
    when (role) {
        Role.OWNER -> R.string.role_owner
        Role.MANAGER -> R.string.role_manager
        Role.CASHIER -> R.string.role_cashier
        Role.PLATFORM_ADMIN -> R.string.role_platform_admin
    }
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StaffScreen(onBack: () -> Unit, vm: StaffViewModel = hiltViewModel()) {
    val staff by vm.staff.collectAsStateWithLifecycle()
    val staffMode by vm.staffMode.collectAsStateWithLifecycle()
    val actor by vm.actor.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    var enabling by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<StaffEntity?>(null) }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.staff_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            )
        },
        floatingActionButton = {
            if (staffMode) ExtendedFloatingActionButton(onClick = { adding = true }, icon = { Icon(Icons.Filled.PersonAdd, null) }, text = { Text(stringResource(R.string.add_staff)) })
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!staffMode) {
                item {
                    Text(stringResource(R.string.staff_off_title), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.staff_off_body), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { enabling = true }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text(stringResource(R.string.staff_turn_on)) }
                }
            } else {
                item {
                    Text(stringResource(R.string.signed_in_as, actor.name, roleLabel(actor.role)), style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = vm::switchUser) { Text(stringResource(R.string.switch_user)) }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Text(stringResource(R.string.cashier_rules), style = MaterialTheme.typography.titleSmall)
                    SwitchRow(stringResource(R.string.cashier_discount), settings.cashierCanDiscount, vm::setCashierDiscount)
                    SwitchRow(stringResource(R.string.cashier_credit), settings.cashierCanSellOnCredit, vm::setCashierCredit)
                    SwitchRow(stringResource(R.string.cashier_batch_override), settings.cashierCanOverrideBatch, vm::setCashierBatchOverride)
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Text(stringResource(R.string.staff_list), style = MaterialTheme.typography.titleSmall)
                }
                if (staff == null) {
                    item { LoadingState(Modifier.fillMaxWidth().height(180.dp)) }
                } else {
                    items(staff.orEmpty(), key = { it.id }) { s ->
                        ListItem(
                            modifier = Modifier.clickable { editing = s },
                            headlineContent = { Text(s.name, style = MaterialTheme.typography.titleMedium) },
                            supportingContent = { Text(roleLabel(s.role) + if (!s.isActive) " · " + stringResource(R.string.staff_inactive) else "") },
                        )
                        HorizontalDivider()
                    }
                }
                item { Text(stringResource(R.string.staff_pin_note), style = MaterialTheme.typography.bodySmall) }
            }
        }
    }

    if (enabling) PinFormDialog(R.string.staff_turn_on, nameLabel = R.string.field_owner_name, initialName = "Owner", askRole = false, onDismiss = { enabling = false },
        onSave = { name, _, pin, again -> vm.enable(name, pin, again) })
    if (adding) PinFormDialog(R.string.add_staff, nameLabel = R.string.field_staff_name, initialName = "", askRole = true, onDismiss = { adding = false },
        onSave = { name, role, pin, again -> vm.add(name, role, pin, again) })
    editing?.let { s -> EditStaffDialog(s, vm) { editing = null } }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun PinField(value: String, onChange: (String) -> Unit, label: Int) {
    OutlinedTextField(value, { if (it.length <= 6 && it.all(Char::isDigit)) onChange(it) }, singleLine = true, label = { Text(stringResource(label)) },
        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
}

/** Name + (optional) role + PIN twice. [onSave] returns a string-resource id for a problem, or null when done. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PinFormDialog(
    title: Int, nameLabel: Int, initialName: String, askRole: Boolean, onDismiss: () -> Unit,
    onSave: suspend (String, Role, String, String) -> Int?,
) {
    var name by remember { mutableStateOf(initialName) }
    var role by remember { mutableStateOf(Role.CASHIER) }
    var pin by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<Int?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(stringResource(title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it; error = null }, singleLine = true, label = { Text(stringResource(nameLabel)) })
                if (askRole) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(Role.CASHIER, Role.MANAGER).forEach { r -> FilterChip(role == r, { role = r }, { Text(roleLabel(r)) }) }
                }
                PinField(pin, { pin = it; error = null }, R.string.field_pin)
                PinField(again, { again = it; error = null }, R.string.field_pin_again)
                error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && name.isNotBlank() && pin.length >= 4, onClick = {
                busy = true
                scope.launch { val e = onSave(name, role, pin, again); busy = false; if (e == null) onDismiss() else error = e }
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditStaffDialog(s: StaffEntity, vm: StaffViewModel, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(s.name) }
    var role by remember { mutableStateOf(s.role) }
    var active by remember { mutableStateOf(s.isActive) }
    var pin by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()
    val isOwner = s.role == Role.OWNER
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(s.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it; error = null }, singleLine = true, label = { Text(stringResource(R.string.field_staff_name)) })
                if (isOwner) Text(roleLabel(Role.OWNER), style = MaterialTheme.typography.bodyMedium)
                else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(Role.CASHIER, Role.MANAGER).forEach { r -> FilterChip(role == r, { role = r }, { Text(roleLabel(r)) }) }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.staff_active), Modifier.weight(1f)); Switch(active, { active = it })
                    }
                }
                Text(stringResource(R.string.staff_new_pin_hint), style = MaterialTheme.typography.labelMedium)
                PinField(pin, { pin = it; error = null }, R.string.field_pin)
                PinField(again, { again = it; error = null }, R.string.field_pin_again)
                error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    if (pin.isNotEmpty()) {
                        val pinError = when {
                            pin != again -> R.string.err_pin_mismatch
                            else -> when (PinPolicy.validate(pin)) {
                                PinPolicy.Result.OK -> null
                                PinPolicy.Result.BAD_LENGTH -> R.string.err_pin_length
                                PinPolicy.Result.NOT_DIGITS -> R.string.err_pin_digits
                                PinPolicy.Result.TOO_SIMPLE -> R.string.err_pin_simple
                            }
                        }
                        if (pinError != null) {
                            error = pinError
                            return@launch
                        }
                    }
                    var e = vm.update(s.id, name, role, active)
                    if (e == null && pin.isNotEmpty()) e = vm.resetPin(s.id, pin, again)
                    if (e == null) onDismiss() else error = e
                }
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
