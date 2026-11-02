package com.xcloak.storevia.feature.customers

import com.xcloak.storevia.core.money.Money

object KhataText {
    /** Plain text a shopkeeper can send over WhatsApp or SMS. */
    fun reminder(storeName: String, customerName: String, balancePaise: Long): String =
        "Hello $customerName, this is a gentle reminder from $storeName. " +
            "Your pending balance is ${Money(balancePaise).format()}. " +
            "Please pay when convenient. Thank you!"
}
