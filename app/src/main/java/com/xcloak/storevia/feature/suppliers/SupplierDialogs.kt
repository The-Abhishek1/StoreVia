package com.xcloak.storevia.feature.suppliers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.xcloak.storevia.R
import com.xcloak.storevia.core.database.entity.SupplierEntity

/** Add or edit a supplier. [onSave] returns false when the GSTIN is not valid. */
@Composable
fun SupplierFormDialog(initial: SupplierEntity?, onSave: (name: String, phone: String, gstin: String) -> Boolean, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var phone by remember { mutableStateOf(initial?.phone.orEmpty()) }
    var gstin by remember { mutableStateOf(initial?.gstin.orEmpty()) }
    var invalid by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (initial == null) R.string.add_supplier else R.string.edit_supplier)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, singleLine = true, label = { Text(stringResource(R.string.field_supplier_name)) })
                OutlinedTextField(phone, { phone = it }, singleLine = true, label = { Text(stringResource(R.string.field_phone)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
                OutlinedTextField(gstin, { gstin = it.uppercase(); invalid = false }, singleLine = true, isError = invalid,
                    label = { Text(stringResource(R.string.field_supplier_gstin)) },
                    supportingText = if (invalid) { { Text(stringResource(R.string.err_gstin_checksum)) } } else null,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters))
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { if (onSave(name, phone, gstin)) onDismiss() else invalid = true }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
