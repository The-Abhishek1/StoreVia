package com.xcloak.storevia.feature.customers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.xcloak.storevia.R
import com.xcloak.storevia.core.database.entity.CustomerEntity
import com.xcloak.storevia.core.util.Parsing

/** Used both to add a customer and to edit one. [onSave] returns false when the input is invalid. */
@Composable
fun CustomerFormDialog(
    initial: CustomerEntity?,
    canEditLimit: Boolean = true,
    onSave: (name: String, phone: String, limit: String) -> Boolean,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var phone by remember { mutableStateOf(initial?.phone.orEmpty()) }
    var limit by remember { mutableStateOf(initial?.creditLimitPaise?.let { Parsing.paiseToInput(it) }.orEmpty()) }
    var invalid by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (initial == null) R.string.add_customer else R.string.edit_customer)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, singleLine = true, label = { Text(stringResource(R.string.field_customer_name)) })
                OutlinedTextField(phone, { phone = it }, singleLine = true, label = { Text(stringResource(R.string.field_phone)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
                if (canEditLimit) OutlinedTextField(limit, { limit = it; invalid = false }, singleLine = true, isError = invalid,
                    label = { Text(stringResource(R.string.field_credit_limit)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = { if (onSave(name, phone, limit)) onDismiss() else invalid = true },
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
