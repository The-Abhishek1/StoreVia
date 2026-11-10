-- Recreate checkout with explicit PL/pgSQL variable precedence for sale_id.
create or replace function public.storevia_checkout(
  p_store_id text,
  p_request_id text,
  p_payload jsonb
) returns text
language plpgsql security definer set search_path = public as $$
#variable_conflict use_variable
declare
  receipt public.storevia_transaction_receipts%rowtype;
  shop public."store"%rowtype;
  branch text;
  sale_id text := gen_random_uuid()::text;
  invoice_no text;
  customer_id text := nullif(p_payload->>'customer_id', '');
  created_at_ms bigint := coalesce((p_payload->>'created_at')::bigint, (extract(epoch from clock_timestamp()) * 1000)::bigint);
  items jsonb := coalesce(p_payload->'items', '[]'::jsonb);
  payments jsonb := coalesce(p_payload->'payments', '[]'::jsonb);
  item jsonb;
  pay jsonb;
  product_row public."products"%rowtype;
  batch_row public."batches"%rowtype;
  line_id text;
  qty bigint;
  remaining bigint;
  batch_take bigint;
  gross bigint;
  line_discount bigint;
  bill_discount bigint := coalesce((p_payload->>'bill_discount_paise')::bigint, 0);
  net_total bigint := 0;
  net_line bigint;
  share bigint;
  share_left bigint := 0;
  gross_left bigint;
  discount_left bigint;
  alloc_gross bigint;
  alloc_discount bigint;
  taxable bigint;
  tax bigint;
  cgst bigint;
  sgst bigint;
  igst bigint;
  line_total bigint;
  line_sum bigint := 0;
  subtotal bigint := 0;
  discount_total bigint := 0;
  tax_total bigint := 0;
  total bigint;
  round_off bigint;
  paid bigint := 0;
  credit bigint;
  rate numeric;
  inter_state boolean := coalesce((p_payload->>'inter_state')::boolean, false);
  allow_negative boolean;
  preferred text;
  allocated_batch text;
  cost bigint;
  item_index integer := 0;
  item_count integer := jsonb_array_length(items);
  movement_id text;
  serial_row record;
  needed_serials bigint;
  serial_count bigint;
  actor_role text;
begin
  if auth.uid() is null or not public.can_write(p_store_id, array['OWNER','MANAGER','CASHIER']) then
    raise exception 'cloud sale write access required';
  end if;
  if p_request_id is null or btrim(p_request_id) = '' then raise exception 'request id is required'; end if;
  select role into actor_role from public.store_members where store_id = p_store_id and user_id = auth.uid();

  select * into receipt from public.storevia_transaction_receipts
    where store_id = p_store_id and request_id = p_request_id;
  if found then
    if receipt.operation <> 'CHECKOUT' then raise exception 'request id was already used for another operation'; end if;
    return receipt.result_id;
  end if;
  if jsonb_typeof(items) <> 'array' or jsonb_array_length(items) = 0 then raise exception 'cart is empty'; end if;
  if jsonb_typeof(payments) <> 'array' then raise exception 'payments must be an array'; end if;

  select * into shop from public."store" where store_id = p_store_id for update;
  if not found then raise exception 'cloud store settings are missing'; end if;
  select id into branch from public."branches"
    where store_id = p_store_id and deleted_at is null and is_active = 1
    order by created_at, name limit 1;
  branch := coalesce(branch, p_store_id);
  allow_negative := shop.allow_negative_stock <> 0;

  for item in select value from jsonb_array_elements(items) loop
    qty := (item->>'qty_milli')::bigint;
    if qty <= 0 then raise exception 'quantity must be positive'; end if;
    select * into product_row from public."products"
      where store_id = p_store_id and id = item->>'product_id'
        and deleted_at is null and is_active = 1;
    if not found then raise exception 'product is unavailable'; end if;
    if (item->>'unit_price_paise')::bigint < 0 then raise exception 'unit price must not be negative'; end if;
    if (item->>'unit_price_paise')::bigint is distinct from product_row.sell_price_paise and actor_role not in ('OWNER','MANAGER') then
      raise exception 'price override requires manager approval';
    end if;
    if coalesce((item->>'discount_paise')::bigint, 0) > 0 and actor_role not in ('OWNER','MANAGER') then
      raise exception 'discount requires manager approval';
    end if;
    if nullif(item->>'preferred_batch_id', '') is not null and actor_role not in ('OWNER','MANAGER') then
      raise exception 'batch override requires manager approval';
    end if;
    if exists (select 1 from public."recipe_components" rc where rc.store_id = p_store_id
        and rc.recipe_product_id = product_row.id and rc.deleted_at is null) then
      raise exception 'cloud checkout for recipe products is not supported by this transaction version';
    end if;
    if product_row.serial_tracked <> 0 and qty % 1000 <> 0 then raise exception 'serial-tracked products require whole units'; end if;
    gross := public.storevia_round_half_up((item->>'unit_price_paise')::numeric * qty / 1000);
    line_discount := coalesce((item->>'discount_paise')::bigint, 0);
    if line_discount < 0 or line_discount > gross then raise exception 'invalid line discount'; end if;
    net_total := net_total + gross - line_discount;
  end loop;
  if bill_discount < 0 or bill_discount > net_total then raise exception 'invalid bill discount'; end if;
  if bill_discount > 0 and actor_role not in ('OWNER','MANAGER') then raise exception 'discount requires manager approval'; end if;

  for pay in select value from jsonb_array_elements(payments) loop
    if (pay->>'amount_paise')::bigint <= 0 or
       coalesce(pay->>'method', '') not in ('CASH','UPI','CARD','OTHER') then
      raise exception 'invalid payment';
    end if;
    paid := paid + (pay->>'amount_paise')::bigint;
  end loop;

  invoice_no := shop.invoice_prefix || '-' || lpad(shop.next_invoice_no::text, 5, '0');
  update public."store" set next_invoice_no = next_invoice_no + 1 where store_id = p_store_id;
  insert into public."sales" (
    id, invoice_no, branch_id, customer_id, status, inter_state, subtotal_paise, discount_paise,
    tax_paise, round_off_paise, total_paise, paid_paise, returned_paise, created_at, store_id
  ) values (sale_id, invoice_no, branch, customer_id, 'COMPLETED', inter_state::int, 0,
    0, 0, 0, 0, 0, 0, created_at_ms, p_store_id);

  -- Allocate discounts and FEFO stock under row locks; every write below shares this RPC transaction.
  for item in select value from jsonb_array_elements(items) loop
    item_index := item_index + 1;
    qty := (item->>'qty_milli')::bigint;
    select * into product_row from public."products"
      where store_id = p_store_id and id = item->>'product_id' and deleted_at is null and is_active = 1;
    gross := public.storevia_round_half_up((item->>'unit_price_paise')::numeric * qty / 1000);
    line_discount := coalesce((item->>'discount_paise')::bigint, 0);
    net_line := gross - line_discount;
    if item_index = item_count then
      share := bill_discount - share_left;
    else
      share := public.storevia_round_half_up(bill_discount::numeric * net_line / nullif(net_total, 0));
      share_left := coalesce(share_left, 0) + share;
    end if;
    line_discount := line_discount + share;
    preferred := nullif(item->>'preferred_batch_id', '');
    if preferred is not null then
      select * into batch_row from public."batches"
        where store_id = p_store_id and id = preferred and product_id = product_row.id
          and branch_id = branch and deleted_at is null and qty_on_hand_milli > 0
          and (expiry_date is null or expiry_date >= current_date::text)
        for update;
      if not found then raise exception 'selected batch is unavailable or expired'; end if;
    end if;
    remaining := qty;
    gross_left := gross;
    discount_left := line_discount;
    for batch_row in
      select * from public."batches"
      where store_id = p_store_id and product_id = product_row.id and branch_id = branch
        and deleted_at is null and qty_on_hand_milli > 0
        and (expiry_date is null or expiry_date >= current_date::text)
      order by case when id = preferred then 0 else 1 end,
        expiry_date nulls last, received_at, id
      for update
    loop
      exit when remaining = 0;
      batch_take := least(remaining, batch_row.qty_on_hand_milli);
      if preferred is not null and batch_row.id = preferred and batch_take < remaining then
        -- Selected batch is preferred, not exclusive; continue to FEFO after it.
        null;
      end if;
      alloc_gross := case when batch_take = remaining then gross_left
        else public.storevia_round_half_up(gross::numeric * batch_take / qty) end;
      alloc_discount := case when batch_take = remaining then discount_left
        else public.storevia_round_half_up(line_discount::numeric * batch_take / qty) end;
      rate := product_row.gst_rate_bp::numeric;
      if product_row.tax_inclusive <> 0 then
        taxable := public.storevia_round_half_up((alloc_gross - alloc_discount)::numeric * 10000 / (10000 + rate));
        tax := alloc_gross - alloc_discount - taxable;
      else
        taxable := alloc_gross - alloc_discount;
        tax := public.storevia_round_half_up(taxable::numeric * rate / 10000);
      end if;
      if inter_state then
        cgst := 0; sgst := 0; igst := tax;
      else
        cgst := public.storevia_round_half_up(tax::numeric / 2);
        sgst := tax - cgst; igst := 0;
      end if;
      line_total := taxable + tax;
      line_id := gen_random_uuid()::text;
      insert into public."sale_lines" (
        id, sale_id, product_id, batch_id, name_snapshot, hsn_snapshot, qty_milli,
        unit_price_paise, discount_paise, gst_rate_bp, tax_inclusive, taxable_value_paise,
        cgst_paise, sgst_paise, igst_paise, line_total_paise, cost_snapshot_paise,
        returned_qty_milli, returned_amount_paise, store_id
      ) values (
        line_id, sale_id, product_row.id, batch_row.id, product_row.name, product_row.hsn_code, batch_take,
        (item->>'unit_price_paise')::bigint, alloc_discount, product_row.gst_rate_bp,
        product_row.tax_inclusive, taxable, cgst, sgst, igst, line_total, batch_row.cost_paise,
        0, 0, p_store_id
      );
      update public."batches" set qty_on_hand_milli = qty_on_hand_milli - batch_take,
        updated_at = created_at_ms where store_id = p_store_id and id = batch_row.id;
      movement_id := gen_random_uuid()::text;
      insert into public."stock_movements" (
        id, product_id, branch_id, batch_id, type, qty_milli, unit_cost_paise, ref_type, ref_id, created_at, store_id
      ) values (movement_id, product_row.id, branch, batch_row.id, 'SALE', -batch_take,
        batch_row.cost_paise, 'SALE', sale_id, created_at_ms, p_store_id);
      if product_row.serial_tracked <> 0 then
        needed_serials := batch_take / 1000;
        select count(*) into serial_count from public."serial_numbers"
          where store_id = p_store_id and product_id = product_row.id and batch_id = batch_row.id
            and status = 'AVAILABLE' and deleted_at is null;
        if serial_count < needed_serials then raise exception 'serial numbers are no longer available'; end if;
        for serial_row in select id from public."serial_numbers"
          where store_id = p_store_id and product_id = product_row.id and batch_id = batch_row.id
            and status = 'AVAILABLE' and deleted_at is null order by serial_number limit needed_serials
          for update
        loop
          update public."serial_numbers" set status = 'SOLD', sale_line_id = line_id, updated_at = created_at_ms
            where store_id = p_store_id and id = serial_row.id;
        end loop;
      end if;
      gross_left := gross_left - alloc_gross;
      discount_left := discount_left - alloc_discount;
      remaining := remaining - batch_take;
    end loop;
    if remaining > 0 then
      if not allow_negative or product_row.serial_tracked <> 0 then raise exception 'insufficient stock'; end if;
      alloc_gross := gross_left;
      alloc_discount := discount_left;
      rate := product_row.gst_rate_bp::numeric;
      if product_row.tax_inclusive <> 0 then
        taxable := public.storevia_round_half_up((alloc_gross - alloc_discount)::numeric * 10000 / (10000 + rate));
        tax := alloc_gross - alloc_discount - taxable;
      else
        taxable := alloc_gross - alloc_discount;
        tax := public.storevia_round_half_up(taxable::numeric * rate / 10000);
      end if;
      if inter_state then cgst := 0; sgst := 0; igst := tax;
      else cgst := public.storevia_round_half_up(tax::numeric / 2); sgst := tax - cgst; igst := 0; end if;
      line_total := taxable + tax;
      insert into public."sale_lines" (
        id, sale_id, product_id, batch_id, name_snapshot, hsn_snapshot, qty_milli,
        unit_price_paise, discount_paise, gst_rate_bp, tax_inclusive, taxable_value_paise,
        cgst_paise, sgst_paise, igst_paise, line_total_paise, cost_snapshot_paise,
        returned_qty_milli, returned_amount_paise, store_id
      ) values (
        gen_random_uuid()::text, sale_id, product_row.id, null, product_row.name, product_row.hsn_code, remaining,
        (item->>'unit_price_paise')::bigint, alloc_discount, product_row.gst_rate_bp,
        product_row.tax_inclusive, taxable, cgst, sgst, igst, line_total, product_row.cost_paise,
        0, 0, p_store_id
      );
      insert into public."stock_movements" (
        id, product_id, branch_id, batch_id, type, qty_milli, unit_cost_paise, ref_type, ref_id, created_at, store_id
      ) values (gen_random_uuid()::text, product_row.id, branch, null, 'SALE', -remaining,
        product_row.cost_paise, 'SALE', sale_id, created_at_ms, p_store_id);
    end if;
  end loop;

  select coalesce(sum(taxable_value_paise), 0), coalesce(sum(discount_paise), 0),
    coalesce(sum(cgst_paise + sgst_paise + igst_paise), 0), coalesce(sum(line_total_paise), 0)
    into subtotal, discount_total, tax_total, line_sum
    from public."sale_lines" sl where sl.store_id = p_store_id and sl.sale_id = sale_id;
  total := public.storevia_round_half_up(line_sum::numeric / 100) * 100;
  round_off := total - line_sum;
  if paid > total then raise exception 'payment exceeds bill total'; end if;
  credit := total - paid;
  if credit > 0 and customer_id is null and not coalesce((p_payload->>'quote')::boolean, false) then
    raise exception 'choose a customer to sell on credit';
  end if;
  if credit > 0 and customer_id is not null and actor_role = 'CASHIER' then raise exception 'credit sale requires manager approval'; end if;
  if customer_id is not null and not exists (select 1 from public."customers"
    where store_id = p_store_id and id = customer_id and deleted_at is null and is_active = 1) then
    raise exception 'customer is unavailable';
  end if;
  if credit > 0 and customer_id is not null and (select credit_limit_paise from public."customers"
      where store_id = p_store_id and id = customer_id) is not null
      and (select coalesce(sum(cl.amount_paise), 0) from public."customer_ledger" cl
        where cl.store_id = p_store_id and cl.customer_id = customer_id) + credit >
      (select credit_limit_paise from public."customers" where store_id = p_store_id and id = customer_id) then
    raise exception 'credit limit exceeded';
  end if;

  update public."sales" set subtotal_paise = subtotal, discount_paise = discount_total,
    tax_paise = tax_total, round_off_paise = round_off, total_paise = total, paid_paise = paid
    where store_id = p_store_id and id = sale_id;
  -- Sale lines were inserted first so each line and stock event is covered by this transaction.
  for pay in select value from jsonb_array_elements(payments) loop
    insert into public."payments" (id, sale_id, customer_id, method, amount_paise, reference, created_at, store_id)
    values (gen_random_uuid()::text, sale_id, customer_id, pay->>'method',
      (pay->>'amount_paise')::bigint, nullif(pay->>'reference', ''), created_at_ms, p_store_id);
  end loop;
  if credit > 0 and customer_id is not null then
    insert into public."customer_ledger" (id, customer_id, type, amount_paise, ref_type, ref_id, created_at, store_id)
    values (gen_random_uuid()::text, customer_id, 'SALE_ON_CREDIT', credit, 'SALE', sale_id, created_at_ms, p_store_id);
  end if;
  insert into public.storevia_transaction_receipts (store_id, request_id, operation, result_id)
    values (p_store_id, p_request_id, 'CHECKOUT', sale_id);
  return sale_id;
end;
$$;

