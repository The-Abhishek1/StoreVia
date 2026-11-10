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
import com.xcloak.storevia.core.access.Permission
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
    val cloudMode by vm.cloudMode.collectAsStateWithLifecycle()
    val loadError by vm.loadError.collectAsStateWithLifecycle()
    val staffInvites by vm.staffInvites.collectAsStateWithLifecycle()
    val inviteLoadError by vm.inviteLoadError.collectAsStateWithLifecycle()
    val canManageStaff = actor.can(Permission.MANAGE_STAFF)
    var enabling by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var inviting by remember { mutableStateOf(false) }
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
            if (cloudMode && actor.role == Role.OWNER) {
                ExtendedFloatingActionButton(onClick = { inviting = true }, icon = { Icon(Icons.Filled.PersonAdd, null) }, text = { Text(stringResource(R.string.cloud_staff_invite)) })
            } else if (!cloudMode && staffMode && canManageStaff) {
                ExtendedFloatingActionButton(onClick = { adding = true }, icon = { Icon(Icons.Filled.PersonAdd, null) }, text = { Text(stringResource(R.string.add_staff)) })
            }
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (cloudMode) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.cloud_staff_title), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(R.string.cloud_staff_body), style = MaterialTheme.typography.bodyMedium)
                            Text(stringResource(R.string.cloud_staff_pin_unsupported), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (actor.role == Role.OWNER) {
                                Text(stringResource(R.string.cloud_staff_owner_invite_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.staff_list), style = MaterialTheme.typography.titleMedium)
                }
                if (staff == null) {
                    item { LoadingState(Modifier.fillMaxWidth().height(180.dp)) }
                } else if (loadError != null) {
                    item {
                        Text(stringResource(loadError!!), color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = vm::refresh) { Text(stringResource(R.string.retry)) }
                    }
                } else {
                    if (staff.orEmpty().isEmpty()) {
                        item { Text(stringResource(R.string.cloud_staff_empty), style = MaterialTheme.typography.bodyMedium) }
                    } else {
                        items(staff.orEmpty(), key = { it.id }) { member ->
                            ListItem(
                                headlineContent = { Text(member.name, style = MaterialTheme.typography.titleMedium) },
                                supportingContent = {
                                    Text("${roleLabel(member.role)} · ${stringResource(R.string.cloud_member_role)}")
                                },
                            )
                            HorizontalDivider()
                        }
                    }
                    if (actor.role == Role.OWNER) {
                        item {
                            Spacer(Modifier.height(8.dp))
                            Text(stringResource(R.string.cloud_staff_pending_title), style = MaterialTheme.typography.titleMedium)
                            if (inviteLoadError != null) {
                                Text(stringResource(inviteLoadError!!), color = MaterialTheme.colorScheme.error)
                                TextButton(onClick = vm::refresh) { Text(stringResource(R.string.retry)) }
                            } else if (staffInvites.isEmpty()) {
                                Text(stringResource(R.string.cloud_staff_pending_empty), style = MaterialTheme.typography.bodyMedium)
                            } else {
                                Text(stringResource(R.string.cloud_staff_pending_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        items(staffInvites, key = { it.email }) { invite ->
                            ListItem(
                                headlineContent = { Text(invite.email, style = MaterialTheme.typography.titleSmall) },
                                supportingContent = { Text(stringResource(R.string.cloud_staff_invite_pending, roleLabel(invite.role))) },
                            )
                            HorizontalDivider()
                        }
                    }
                }
            } else if (!staffMode) {
                item {
                    Text(stringResource(R.string.staff_off_title), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.staff_off_body), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { enabling = true }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text(stringResource(R.string.staff_turn_on)) }
                }
            } else {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.staff_signed_in_heading), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(stringResource(R.string.signed_in_as, actor.name, roleLabel(actor.role)), style = MaterialTheme.typography.titleMedium)
                            OutlinedButton(onClick = vm::switchUser) { Text(stringResource(R.string.switch_user)) }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.staff_roles_heading), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    RoleGuideCard(Role.OWNER)
                    Spacer(Modifier.height(8.dp))
                    RoleGuideCard(Role.MANAGER)
                    Spacer(Modifier.height(8.dp))
                    RoleGuideCard(Role.CASHIER)
                    Spacer(Modifier.height(8.dp))
                    if (canManageStaff) {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(stringResource(R.string.cashier_rules), style = MaterialTheme.typography.titleMedium)
                                Text(stringResource(R.string.cashier_rules_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                SwitchRow(stringResource(R.string.cashier_discount), stringResource(R.string.cashier_discount_hint), settings.cashierCanDiscount, vm::setCashierDiscount)
                                HorizontalDivider()
                                SwitchRow(stringResource(R.string.cashier_credit), stringResource(R.string.cashier_credit_hint), settings.cashierCanSellOnCredit, vm::setCashierCredit)
                                HorizontalDivider()
                                SwitchRow(stringResource(R.string.cashier_batch_override), stringResource(R.string.cashier_batch_override_hint), settings.cashierCanOverrideBatch, vm::setCashierBatchOverride)
                            }
                        }
                    } else {
                        Text(stringResource(R.string.staff_cashier_options_owner_only), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.staff_list), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(R.string.staff_list_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (staff == null) {
                    item { LoadingState(Modifier.fillMaxWidth().height(180.dp)) }
                } else {
                    items(staff.orEmpty(), key = { it.id }) { s ->
                        ListItem(
                            modifier = if (canManageStaff) Modifier.clickable { editing = s } else Modifier,
                            headlineContent = { Text(s.name, style = MaterialTheme.typography.titleMedium) },
                            supportingContent = {
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(roleLabel(s.role) + " · " + stringResource(roleDescription(s.role)))
                                    Text(
                                        stringResource(if (s.isActive) R.string.staff_active else R.string.staff_inactive),
                                        color = if (s.isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                            },
                            trailingContent = if (canManageStaff) {
                                { Text(stringResource(R.string.staff_edit_action), color = MaterialTheme.colorScheme.primary) }
                            } else null,
                        )
                        HorizontalDivider()
                    }
                }
                item { Text(stringResource(R.string.staff_pin_note), style = MaterialTheme.typography.bodySmall) }
            }
        }
    }

    if (!cloudMode && enabling) PinFormDialog(R.string.staff_turn_on, nameLabel = R.string.field_owner_name, initialName = "Owner", askRole = false, onDismiss = { enabling = false },
        onSave = { name, _, pin, again -> vm.enable(name, pin, again) })
    if (!cloudMode && adding) PinFormDialog(R.string.add_staff, nameLabel = R.string.field_staff_name, initialName = "", askRole = true, onDismiss = { adding = false },
        onSave = { name, role, pin, again -> vm.add(name, role, pin, again) })
    if (!cloudMode) editing?.let { s -> EditStaffDialog(s, vm) { editing = null } }
    if (cloudMode && inviting) CloudStaffInviteDialog(vm, onDismiss = { inviting = false })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CloudStaffInviteDialog(vm: StaffViewModel, onDismiss: () -> Unit) {
    var email by remember { mutableStateOf("") }
    var role by remember { mutableStateOf(Role.CASHIER) }
    var error by remember { mutableStateOf<Int?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.cloud_staff_invite_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.cloud_staff_invite_body), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it; error = null },
                    singleLine = true,
                    label = { Text(stringResource(R.string.cloud_staff_email)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    enabled = !busy,
                )
                Text(stringResource(R.string.staff_choose_role), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(Role.CASHIER, Role.MANAGER).forEach { candidate ->
                        FilterChip(
                            selected = role == candidate,
                            onClick = { role = candidate },
                            label = { Text(roleLabel(candidate)) },
                            enabled = !busy,
                        )
                    }
                }
                error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && email.isNotBlank(), onClick = {
                busy = true
                scope.launch {
                    val result = vm.inviteCloudStaff(email, role)
                    busy = false
                    if (result == null) onDismiss() else error = result
                }
            }) { Text(stringResource(R.string.cloud_staff_invite)) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun RoleGuideCard(role: Role) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(roleLabel(role), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(roleDescription(role)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun roleDescription(role: Role): Int = when (role) {
    Role.OWNER -> R.string.staff_role_owner_description
    Role.MANAGER -> R.string.staff_role_manager_description
    Role.CASHIER -> R.string.staff_role_cashier_description
    Role.PLATFORM_ADMIN -> R.string.role_platform_admin
}

@Composable
private fun SwitchRow(label: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
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
                if (askRole) {
                    Text(stringResource(R.string.staff_choose_role), style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(Role.CASHIER, Role.MANAGER).forEach { r -> FilterChip(role == r, { role = r }, { Text(roleLabel(r)) }) }
                    }
                    Text(stringResource(roleDescription(role)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    Text(stringResource(R.string.staff_choose_role), style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(Role.CASHIER, Role.MANAGER).forEach { r -> FilterChip(role == r, { role = r }, { Text(roleLabel(r)) }) }
                    }
                    Text(stringResource(roleDescription(role)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(if (active) R.string.staff_active else R.string.staff_inactive))
                            Text(stringResource(R.string.staff_active_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(active, { active = it })
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
