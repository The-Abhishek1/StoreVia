package com.xcloak.storevia.feature.sales

import com.xcloak.storevia.feature.staff.permissionMessage
import com.xcloak.storevia.core.access.*
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xcloak.storevia.R
import com.xcloak.storevia.core.database.dao.BatchDao
import com.xcloak.storevia.core.database.dao.BranchDao
import com.xcloak.storevia.core.database.dao.CustomerDao
import com.xcloak.storevia.core.database.dao.HeldCartDao
import com.xcloak.storevia.core.database.dao.ProductDao
import com.xcloak.storevia.core.database.dao.ProductListRow
import com.xcloak.storevia.core.database.dao.UnitDao
import com.xcloak.storevia.core.database.entity.CustomerEntity
import com.xcloak.storevia.core.database.entity.BatchEntity
import com.xcloak.storevia.core.database.entity.HeldCartEntity
import com.xcloak.storevia.core.database.entity.PaymentMethod
import com.xcloak.storevia.core.money.Money
import com.xcloak.storevia.core.money.Rounding
import com.xcloak.storevia.core.util.Parsing
import com.xcloak.storevia.core.util.PackUnit
import com.xcloak.storevia.domain.sale.*
import com.xcloak.storevia.feature.stock.FieldError
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

@Serializable
data class CartLine(
    val productId: String,
    val name: String,
    val unitSymbol: String,
    val allowDecimal: Boolean,
    /** The product's catalogue price, so an override can show "was ₹x". */
    val listPricePaise: Long,
    val unitPricePaise: Long,
    val qtyMilli: Long,
    val discountPaise: Long = 0,
    val baseUnitSymbol: String = unitSymbol,
    val baseAllowDecimal: Boolean = allowDecimal,
    val packUnitSymbol: String? = null,
    val packSizeMilli: Long? = null,
    val usingPackUnit: Boolean = false,
    val preferredBatchId: String? = null,
    val preferredBatchLabel: String? = null,
) {
    val grossPaise: Long get() = Money(unitPricePaise).timesQtyMilli(qtyMilli).paise
    val displayQtyMilli: Long
        get() = if (usingPackUnit) PackUnit(packUnitSymbol!!, packSizeMilli!!).fromBaseQuantity(qtyMilli) else qtyMilli
    val quantityStepMilli: Long get() = if (usingPackUnit) packSizeMilli!! else 1_000L

    fun withUnit(pack: Boolean): CartLine = copy(
        unitSymbol = if (pack) packUnitSymbol!! else baseUnitSymbol,
        allowDecimal = if (pack) false else baseAllowDecimal,
        usingPackUnit = pack,
    )
}

/** The three ways a shopkeeper takes money. Anything not covered goes on the customer's khata. */
val PAY_METHODS = listOf(PaymentMethod.CASH, PaymentMethod.UPI, PaymentMethod.CARD)

data class CheckoutUi(
    val cart: List<CartLine> = emptyList(),
    val query: String = "",
    val unknownBarcode: String? = null,
    val batchOptions: Map<String, List<BatchEntity>> = emptyMap(),
    val batchPickerProductId: String? = null,
    val customer: CustomerEntity? = null,
    val interState: Boolean = false,
    val billDiscountPaise: Long = 0,
    /** What the customer hands over per method. null = the whole bill, in cash. */
    val paidInputs: Map<PaymentMethod, String>? = null,
    val preview: com.xcloak.storevia.domain.sale.SalePreview? = null,
    val message: FieldError? = null,
    val completing: Boolean = false,
) {
    val totalPaise: Long get() = preview?.totals?.totalPaise ?: 0L
    /** Cart value after line discounts, before the bill discount. */
    val netLinesPaise: Long get() = cart.sumOf { it.grossPaise - it.discountPaise }

    /** Amount received in total, or null if any field isn't a valid amount. */
    val paidPaise: Long? get() {
        val inputs = paidInputs ?: return totalPaise
        var sum = 0L
        for (m in PAY_METHODS) {
            val t = inputs[m].orEmpty()
            sum += if (t.isBlank()) 0L else Parsing.moneyToPaise(t) ?: return null
        }
        return sum
    }
    val khataPaise: Long get() = (totalPaise - (paidPaise ?: totalPaise)).coerceAtLeast(0)

    /** What a payment field shows: typed text, or the default (whole bill in cash). */
    fun fieldText(m: PaymentMethod): String = paidInputs?.get(m) ?: if (m == PaymentMethod.CASH) Parsing.paiseToInput(totalPaise) else ""
}

sealed interface CheckoutEvent {
    data class Completed(val saleId: String) : CheckoutEvent
    data class CreateProductForBarcode(val barcode: String) : CheckoutEvent
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CheckoutViewModel @Inject constructor(
    private val handle: SavedStateHandle,
    private val productDao: ProductDao,
    private val batchDao: BatchDao,
    private val unitDao: UnitDao,
    private val customerDao: CustomerDao,
    private val heldCartDao: HeldCartDao,
    private val branchDao: BranchDao,
    private val sale: CompleteSaleUseCase,
    private val access: Access,
) : ViewModel() {

    private val _ui = MutableStateFlow(CheckoutUi())
    val ui: StateFlow<CheckoutUi> = _ui.asStateFlow()

    private val _events = Channel<CheckoutEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    val customers: StateFlow<List<CustomerEntity>> =
        customerDao.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val heldCarts: StateFlow<List<HeldCartEntity>> =
        heldCartDao.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val activeBranchId = branchDao.observeSelectedId().map { it ?: "default" }.distinctUntilChanged()

    val results: StateFlow<List<ProductListRow>> = combine(_ui.map { it.query.trim() }.distinctUntilChanged(), activeBranchId) { q, branchId ->
        q to branchId
    }.flatMapLatest { (q, branchId) ->
        if (q.isEmpty()) flowOf(emptyList()) else productDao.listWithStock(q, branchId).map { it.take(8) }
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var previewJob: Job? = null
    private val json = Json { ignoreUnknownKeys = true }
    private val cartSerializer = ListSerializer(CartLine.serializer())

    init {
        // Barcode handed back by the scanner screen.
        viewModelScope.launch {
            handle.getStateFlow<String?>("barcode", null).filterNotNull().collect { code ->
                handle["barcode"] = null
                val p = productDao.findByBarcode(code)
                if (p != null) addProduct(p.id) else _ui.update { it.copy(unknownBarcode = code, message = null) }
            }
        }
        viewModelScope.launch {
            handle.getStateFlow<String?>("createdProductId", null).filterNotNull().collect { id ->
                handle["createdProductId"] = null
                addProduct(id)
            }
        }
    }

    fun setQuery(q: String) = _ui.update { it.copy(query = q) }

    /** Any change to what is being bought resets how it is paid (so a stale amount can't leave a wrong khata balance). */
    private fun changeCart(transform: (List<CartLine>) -> List<CartLine>) {
        _ui.update { s ->
            val cart = transform(s.cart)
            val net = cart.sumOf { it.grossPaise - it.discountPaise }
            s.copy(cart = cart, billDiscountPaise = s.billDiscountPaise.coerceAtMost(net), paidInputs = null, message = null)
        }
        refreshPreview()
    }

    fun addProduct(id: String) = viewModelScope.launch {
        val p = productDao.getById(id) ?: return@launch
        val unit = unitDao.getById(p.unitId)
        val pack = try {
            PackUnit.fromAttributesJson(p.attributesJson)
        } catch (_: IllegalArgumentException) {
            _ui.update { it.copy(message = FieldError(R.string.err_product_pack_config)) }
            return@launch
        }
        _ui.update { it.copy(query = "") }
        changeCart { cart ->
            val i = cart.indexOfFirst { it.productId == id }
            if (i >= 0) cart.toMutableList().also {
                it[i] = it[i].copy(qtyMilli = Math.addExact(it[i].qtyMilli, it[i].quantityStepMilli))
            }
            else cart + CartLine(
                productId = p.id, name = listOfNotNull(p.name, p.variantLabel).joinToString(" · "), unitSymbol = unit?.symbol.orEmpty(),
                allowDecimal = unit?.allowDecimal ?: false, listPricePaise = p.sellPricePaise,
                unitPricePaise = p.sellPricePaise, qtyMilli = 1_000,
                baseUnitSymbol = unit?.symbol.orEmpty(), baseAllowDecimal = unit?.allowDecimal ?: false,
                packUnitSymbol = pack?.name, packSizeMilli = pack?.baseUnitsPerPackMilli,
            )
        }
    }

    fun step(id: String, deltaSteps: Int) = changeCart { cart ->
        cart.mapNotNull { l ->
            if (l.productId != id) l else {
                val q = l.qtyMilli + deltaSteps * (if (l.usingPackUnit) l.quantityStepMilli else if (l.allowDecimal) 500L else 1000L)
                if (q <= 0) null else l.copy(qtyMilli = q, discountPaise = l.discountPaise.coerceAtMost(Money(l.unitPricePaise).timesQtyMilli(q).paise))
            }
        }
    }

    fun setQty(id: String, text: String) {
        val line = _ui.value.cart.firstOrNull { it.productId == id } ?: return
        val displayQty = Parsing.qtyToMilli(text, line.allowDecimal)
        if (displayQty == null || displayQty <= 0) { fail(if (line.allowDecimal) R.string.err_qty_invalid else R.string.err_qty_whole); return }
        val q = if (line.usingPackUnit) {
            try { PackUnit(line.packUnitSymbol!!, line.packSizeMilli!!).toBaseQuantity(displayQty) }
            catch (_: ArithmeticException) { fail(R.string.err_qty_invalid); return }
        } else displayQty
        changeCart { cart -> cart.map { if (it.productId == id) it.copy(qtyMilli = q, discountPaise = 0) else it } }
    }

    fun usePackUnit(id: String, usePack: Boolean) = changeCart { cart ->
        cart.map { line ->
            if (line.productId == id && line.packUnitSymbol != null && line.packSizeMilli != null) {
                line.withUnit(usePack)
            } else line
        }
    }

    fun openBatchPicker(productId: String) = viewModelScope.launch {
        val options = batchDao.fefoBatches(productId, LocalDate.now(), activeBranchId.first())
        _ui.update {
            it.copy(
                batchOptions = it.batchOptions + (productId to options),
                batchPickerProductId = productId,
            )
        }
    }

    fun selectBatch(productId: String, batch: BatchEntity?) {
        _ui.update { it.copy(batchPickerProductId = null) }
        changeCart { cart ->
            cart.map {
                if (it.productId == productId) it.copy(
                    preferredBatchId = batch?.id,
                    preferredBatchLabel = batch?.batchNo ?: batch?.id?.take(8),
                ) else it
            }
        }
    }

    fun dismissBatchPicker() = _ui.update { it.copy(batchPickerProductId = null) }

    /** Selling at a different price than the catalogue (a bargain, or a loose-item rate). */
    fun setPrice(id: String, text: String) {
        val price = Parsing.moneyToPaise(text)
        if (price == null) { fail(R.string.err_price_negative); return }
        changeCart { cart -> cart.map { if (it.productId == id) it.copy(unitPricePaise = price, discountPaise = 0) else it } }
    }

    fun setDiscount(id: String, text: String) {
        val line = _ui.value.cart.firstOrNull { it.productId == id } ?: return
        val d = if (text.isBlank()) 0L else Parsing.moneyToPaise(text)
        if (d == null || d > line.grossPaise) { fail(R.string.err_discount_invalid); return }
        changeCart { cart -> cart.map { if (it.productId == id) it.copy(discountPaise = d) else it } }
    }

    /** [percent] true: [text] is a percentage of the bill (0-100, up to 2 decimals); false: a rupee amount. */
    fun setBillDiscount(text: String, percent: Boolean) {
        val net = _ui.value.netLinesPaise
        val paise: Long? = when {
            text.isBlank() -> 0L
            percent -> runCatching { BigDecimal(text.trim()) }.getOrNull()
                ?.takeIf { it.signum() >= 0 && it <= BigDecimal(100) && it.scale() <= 2 }
                ?.let { Rounding.divHalfUp(net * it.movePointRight(2).toLong(), 10_000L) }
            else -> Parsing.moneyToPaise(text)
        }
        if (paise == null || paise > net) { fail(R.string.err_bill_discount_invalid); return }
        _ui.update { it.copy(billDiscountPaise = paise, paidInputs = null, message = null) }
        refreshPreview()
    }

    fun remove(id: String) = changeCart { cart -> cart.filterNot { it.productId == id } }

    fun setCustomer(c: CustomerEntity?) = _ui.update { it.copy(customer = c, message = null) }

    fun addCustomer(name: String, phone: String) {
        if (name.isBlank() || !access.actor().can(Permission.MANAGE_CUSTOMERS)) return
        viewModelScope.launch {
            if (!access.actor().can(Permission.MANAGE_CUSTOMERS)) return@launch
            val c = CustomerEntity(name = name.trim(), phone = phone.trim().ifEmpty { null })
            customerDao.insert(c)
            setCustomer(c)
        }
    }

    fun setInterState(v: Boolean) { _ui.update { it.copy(interState = v, paidInputs = null) }; refreshPreview() }

    fun setPaid(method: PaymentMethod, text: String) = _ui.update { s ->
        val base = s.paidInputs ?: mapOf(PaymentMethod.CASH to Parsing.paiseToInput(s.totalPaise))
        s.copy(paidInputs = base + (method to text), message = null)
    }

    /** One tap: the whole bill by [method], or null = nothing now, all on the customer's khata. */
    fun payAll(method: PaymentMethod?) = _ui.update { s ->
        s.copy(paidInputs = if (method == null) PAY_METHODS.associateWith { "" } else mapOf(method to Parsing.paiseToInput(s.totalPaise)), message = null)
    }

    fun dismissMessage() = _ui.update { it.copy(message = null) }
    fun dismissUnknownBarcode() = _ui.update { it.copy(unknownBarcode = null) }
    fun createProductForUnknownBarcode() {
        val barcode = _ui.value.unknownBarcode ?: return
        _ui.update { it.copy(unknownBarcode = null) }
        viewModelScope.launch { _events.send(CheckoutEvent.CreateProductForBarcode(barcode)) }
    }

    private fun fail(res: Int) = _ui.update { it.copy(message = FieldError(res)) }

    // ------------------------------------------------------------------ held bills

    private suspend fun holdCurrent() {
        val s = _ui.value
        if (s.cart.isEmpty()) return
        val label = s.customer?.name
            ?: Instant.now().atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d MMM, h:mm a"))
        heldCartDao.insert(
            HeldCartEntity(
                label = label, customerId = s.customer?.id, interState = s.interState, billDiscountPaise = s.billDiscountPaise,
                itemsJson = json.encodeToString(cartSerializer, s.cart), itemCount = s.cart.size,
            )
        )
    }

    fun hold() = viewModelScope.launch {
        holdCurrent()
        _ui.value = CheckoutUi()
    }

    fun resume(heldId: String) = viewModelScope.launch {
        val h = heldCartDao.getById(heldId) ?: return@launch
        holdCurrent()   // don't lose what is on screen now
        val cart = json.decodeFromString(cartSerializer, h.itemsJson)
        val customer = h.customerId?.let { customerDao.getById(it) }
        heldCartDao.delete(heldId)
        _ui.value = CheckoutUi(cart = cart, customer = customer, interState = h.interState, billDiscountPaise = h.billDiscountPaise)
        refreshPreview()
    }

    fun deleteHeld(heldId: String) = viewModelScope.launch { heldCartDao.delete(heldId) }

    // ------------------------------------------------------------------ totals and completion

    private fun items(s: CheckoutUi) = s.cart.map {
        CartItem(
            productId = it.productId,
            qtyMilli = it.qtyMilli,
            unitPricePaise = it.unitPricePaise,
            discountPaise = it.discountPaise,
            preferredBatchId = it.preferredBatchId,
        )
    }

    private fun refreshPreview() {
        previewJob?.cancel()
        val s = _ui.value
        if (s.cart.isEmpty()) { _ui.update { it.copy(preview = null) }; return }
        previewJob = viewModelScope.launch {
            try {
                val p = sale.preview(items(s), s.interState, billDiscountPaise = s.billDiscountPaise)
                _ui.update { it.copy(preview = p) }
            } catch (e: InsufficientStockException) {
                _ui.update { it.copy(preview = null, message = FieldError(R.string.err_insufficient_stock, e.productName)) }
            } catch (e: SaleValidationException) {
                _ui.update { it.copy(preview = null, message = FieldError(R.string.err_generic, e.message)) }
            }
        }
    }

    fun complete() {
        val s = _ui.value
        if (s.completing || s.cart.isEmpty() || s.preview == null) return
        val paid = s.paidPaise
        val msg = when {
            paid == null -> R.string.err_amount_invalid
            paid > s.totalPaise -> R.string.err_paid_exceeds
            paid < s.totalPaise && s.customer == null -> R.string.err_choose_customer
            else -> null
        }
        if (msg != null) { fail(msg); return }

        val payments = if (s.paidInputs == null) listOfNotNull(s.totalPaise.takeIf { it > 0 }?.let { PaymentInput(PaymentMethod.CASH, it) })
        else PAY_METHODS.mapNotNull { m ->
            val t = s.paidInputs[m].orEmpty()
            (if (t.isBlank()) 0L else Parsing.moneyToPaise(t) ?: 0L).takeIf { it > 0 }?.let { PaymentInput(m, it) }
        }

        viewModelScope.launch {
            _ui.update { it.copy(completing = true) }
            try {
                val id = sale(items(s), payments, s.customer?.id, s.interState, billDiscountPaise = s.billDiscountPaise)
                _events.send(CheckoutEvent.Completed(id))
            } catch (e: InsufficientStockException) {
                _ui.update { it.copy(completing = false, message = FieldError(R.string.err_insufficient_stock, e.productName)) }
            } catch (e: PermissionDeniedException) {
                _ui.update { it.copy(completing = false, message = FieldError(permissionMessage(e.permission))) }
            } catch (e: CreditLimitExceededException) {
                _ui.update { it.copy(completing = false, message = FieldError(R.string.err_credit_limit, Money(e.limitPaise).format())) }
            } catch (e: SaleValidationException) {
                _ui.update { it.copy(completing = false, message = FieldError(R.string.err_generic, e.message)) }
            }
        }
    }
}
