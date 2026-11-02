package com.xcloak.storevia.feature.staff

import com.xcloak.storevia.R
import com.xcloak.storevia.core.access.Permission

/** The message to show when a sensitive action is refused. */
fun permissionMessage(p: Permission): Int = when (p) {
    Permission.SELL_ON_CREDIT -> R.string.err_no_credit_permission
    Permission.APPLY_DISCOUNT -> R.string.err_no_discount_permission
    Permission.OVERRIDE_PRICE -> R.string.err_no_price_permission
    else -> R.string.err_permission_denied
}
