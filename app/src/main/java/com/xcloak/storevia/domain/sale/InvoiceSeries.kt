package com.xcloak.storevia.domain.sale

import com.xcloak.storevia.data.prefs.AppPrefs
import javax.inject.Inject

/**
 * Invoice numbers are counted per phone, so two phones selling at the same moment can never produce the same
 * number: the first phone numbers INV-00001, INV-00002...; a second phone INV-B-00001, INV-B-00002...
 */
interface InvoiceSeries { fun current(): String }

object NoSeries : InvoiceSeries { override fun current() = "" }

class PrefsInvoiceSeries @Inject constructor(private val prefs: AppPrefs) : InvoiceSeries {
    override fun current(): String = prefs.settings.value.deviceSeries
}
