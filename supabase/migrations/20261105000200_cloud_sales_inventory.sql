-- Cloud-authoritative sales and manual inventory transactions.
-- Apply after supabase/schema.sql in the Supabase SQL editor.

alter table public."store"
  add column if not exists "next_invoice_no" bigint not null default 1;
alter table public."sales"
  add column if not exists "subtotal_paise" bigint not null default 0;

create table if not exists public.storevia_transaction_receipts (
  store_id text not null,
  request_id text not null,
  operation text not null,
  result_id text not null,
  created_at timestamptz not null default now(),
  primary key (store_id, request_id)
);
alter table public.storevia_transaction_receipts enable row level security;
revoke all on public.storevia_transaction_receipts from public, anon, authenticated;

create or replace function public.storevia_round_half_up(p_value numeric)
returns bigint
language sql immutable strict as $$
  select floor(p_value + 0.5)::bigint;
$$;

create or replace function public.storevia_checkout(
  p_store_id text,
  p_request_id text,
  p_payload jsonb
) returns text
language plpgsql security definer set search_path = public as $$
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

create or replace function public.storevia_return_sale(
  p_store_id text,
  p_sale_id text,
  p_request_id text,
  p_payload jsonb
) returns text
language plpgsql security definer set search_path = public as $$
declare
  receipt public.storevia_transaction_receipts%rowtype;
  sale_row public."sales"%rowtype;
  requested jsonb;
  item jsonb;
  sale_line record;
  return_id text := gen_random_uuid()::text;
  created_at_ms bigint := coalesce((p_payload->>'created_at')::bigint, (extract(epoch from clock_timestamp()) * 1000)::bigint);
  qty bigint;
  remaining bigint;
  take_qty bigint;
  amount bigint;
  total bigint := 0;
  due_reduced bigint;
  refund bigint;
  method text := coalesce(p_payload->>'refund_method', 'CASH');
  kept_credit boolean := coalesce(p_payload->>'refund_mode', 'MONEY') = 'KHATA_CREDIT';
  all_returned boolean;
  serial_count integer;
  serial_required integer;
begin
  if auth.uid() is null or not public.can_write(p_store_id, array['OWNER','MANAGER']) then
    raise exception 'cloud return write access required';
  end if;
  select * into receipt from public.storevia_transaction_receipts
    where store_id = p_store_id and request_id = p_request_id;
  if found then
    if receipt.operation <> 'RETURN' then raise exception 'request id was already used for another operation'; end if;
    return receipt.result_id;
  end if;
  select * into sale_row from public."sales"
    where store_id = p_store_id and id = p_sale_id for update;
  if not found then raise exception 'sale was not found'; end if;
  if sale_row.status = 'CANCELLED' then raise exception 'sale is cancelled'; end if;
  requested := coalesce(p_payload->'items', '[]'::jsonb);
  if jsonb_typeof(requested) <> 'array' or jsonb_array_length(requested) = 0 then raise exception 'nothing to return'; end if;
  for item in select value from jsonb_array_elements(requested) loop
    qty := (item->>'qty_milli')::bigint;
    if qty <= 0 then raise exception 'return quantity must be positive'; end if;
    remaining := qty;
    for sale_line in select * from public."sale_lines"
      where store_id = p_store_id and sale_id = p_sale_id and product_id = item->>'product_id'
        and returned_qty_milli < qty_milli order by server_updated_at, id for update
    loop
      exit when remaining = 0;
      take_qty := least(remaining, sale_line.qty_milli - sale_line.returned_qty_milli);
      if exists (select 1 from public."stock_movements" sm
        where sm.store_id = p_store_id and sm.ref_type = 'RECIPE_SALE' and sm.reason = sale_line.id) then
        raise exception 'cloud return for recipe sales is not supported by this transaction version';
      end if;
      amount := case when take_qty = sale_line.qty_milli - sale_line.returned_qty_milli
        then sale_line.line_total_paise - sale_line.returned_amount_paise
        else public.storevia_round_half_up(sale_line.line_total_paise::numeric * take_qty / sale_line.qty_milli) end;
      update public."sale_lines" set returned_qty_milli = returned_qty_milli + take_qty,
        returned_amount_paise = returned_amount_paise + amount
        where store_id = p_store_id and id = sale_line.id;
      insert into public."sale_return_lines" (
        id, return_id, sale_line_id, product_id, batch_id, qty_milli, amount_paise, cost_snapshot_paise, store_id
      ) values (gen_random_uuid()::text, return_id, sale_line.id, sale_line.product_id,
        sale_line.batch_id, take_qty, amount, sale_line.cost_snapshot_paise, p_store_id);
      if sale_line.batch_id is not null then
        update public."batches" set qty_on_hand_milli = qty_on_hand_milli + take_qty,
          updated_at = created_at_ms
          where store_id = p_store_id and id = sale_line.batch_id;
      end if;
      insert into public."stock_movements" (
        id, product_id, branch_id, batch_id, type, qty_milli, unit_cost_paise,
        ref_type, ref_id, created_at, store_id
      ) values (gen_random_uuid()::text, sale_line.product_id, sale_row.branch_id, sale_line.batch_id,
        'SALE_RETURN', take_qty, sale_line.cost_snapshot_paise, 'RETURN', return_id, created_at_ms, p_store_id);
      if exists (select 1 from public."serial_numbers" sn
        where sn.store_id = p_store_id and sn.sale_line_id = sale_line.id and sn.status = 'SOLD') then
        if take_qty % 1000 <> 0 then raise exception 'serial-tracked returns require whole units'; end if;
        serial_required := take_qty / 1000;
        select count(*) into serial_count from public."serial_numbers" sn
          where sn.store_id = p_store_id and sn.sale_line_id = sale_line.id and sn.status = 'SOLD';
        if serial_count < serial_required then raise exception 'serial return state is invalid'; end if;
        update public."serial_numbers" set status = 'AVAILABLE', sale_line_id = null, updated_at = created_at_ms
          where store_id = p_store_id and id in (
            select sn.id from public."serial_numbers" sn
            where sn.store_id = p_store_id and sn.sale_line_id = sale_line.id and sn.status = 'SOLD'
            order by sn.serial_number limit serial_required
          );
      end if;
      total := total + amount;
      remaining := remaining - take_qty;
    end loop;
    if remaining > 0 then raise exception 'return quantity exceeds remaining sold quantity'; end if;
  end loop;
  if total = 0 then raise exception 'nothing to return'; end if;
  due_reduced := case when sale_row.customer_id is null then 0
    else least(total, greatest(sale_row.total_paise - sale_row.returned_paise - sale_row.paid_paise, 0)) end;
  refund := total - due_reduced;
  if kept_credit and refund > 0 and sale_row.customer_id is null then raise exception 'khata credit requires a customer'; end if;
  if method not in ('CASH','UPI','CARD','OTHER') then raise exception 'invalid refund method'; end if;
  insert into public."sale_returns" (
    id, sale_id, customer_id, total_paise, due_reduced_paise, refund_paise,
    refund_method, kept_as_credit, created_at, store_id
  ) values (return_id, p_sale_id, sale_row.customer_id, total, due_reduced, refund,
    case when refund > 0 and not kept_credit then method else null end, kept_credit::int, created_at_ms, p_store_id);
  if sale_row.customer_id is not null and (due_reduced > 0 or (kept_credit and refund > 0)) then
    insert into public."customer_ledger" (
      id, customer_id, type, amount_paise, ref_type, ref_id, created_at, store_id
    ) values (gen_random_uuid()::text, sale_row.customer_id, 'RETURN_CREDIT',
      -(due_reduced + case when kept_credit then refund else 0 end), 'RETURN', return_id, created_at_ms, p_store_id);
  end if;
  if refund > 0 and not kept_credit then
    insert into public."payments" (id, sale_id, customer_id, method, amount_paise, reference, created_at, store_id)
    values (gen_random_uuid()::text, p_sale_id, sale_row.customer_id, method, -refund, 'RETURN', created_at_ms, p_store_id);
  end if;
  select not exists (select 1 from public."sale_lines"
    where store_id = p_store_id and sale_id = p_sale_id and returned_qty_milli < qty_milli) into all_returned;
  update public."sales" set returned_paise = returned_paise + total, paid_paise = paid_paise - refund,
    status = case when all_returned then 'RETURNED' else 'PARTIAL_RETURN' end
    where store_id = p_store_id and id = p_sale_id;
  insert into public.storevia_transaction_receipts (store_id, request_id, operation, result_id)
    values (p_store_id, p_request_id, 'RETURN', return_id);
  return return_id;
end;
$$;

create or replace function public.storevia_cancel_sale(
  p_store_id text,
  p_sale_id text,
  p_request_id text,
  p_reason text,
  p_created_at bigint
) returns text
language plpgsql security definer set search_path = public as $$
declare
  receipt public.storevia_transaction_receipts%rowtype;
  sale_row public."sales"%rowtype;
  refund_method text;
  return_request text := p_request_id || ':return';
  items jsonb;
  return_payload jsonb;
begin
  if auth.uid() is null or not public.can_write(p_store_id, array['OWNER','MANAGER']) then
    raise exception 'cloud cancellation write access required';
  end if;
  select * into receipt from public.storevia_transaction_receipts
    where store_id = p_store_id and request_id = p_request_id;
  if found then
    if receipt.operation <> 'CANCEL' then raise exception 'request id was already used for another operation'; end if;
    return receipt.result_id;
  end if;
  select * into sale_row from public."sales" where store_id = p_store_id and id = p_sale_id for update;
  if not found then raise exception 'sale was not found'; end if;
  if sale_row.status = 'CANCELLED' then raise exception 'sale is already cancelled'; end if;
  if sale_row.status <> 'COMPLETED' or sale_row.returned_paise > 0 then raise exception 'sale has returns'; end if;
  select method into refund_method from public."payments"
    where store_id = p_store_id and sale_id = p_sale_id and amount_paise > 0
    order by amount_paise desc, created_at limit 1;
  select jsonb_agg(jsonb_build_object('product_id', product_id, 'qty_milli', sum_qty))
    into items from (
      select product_id, sum(qty_milli)::bigint as sum_qty from public."sale_lines"
      where store_id = p_store_id and sale_id = p_sale_id group by product_id
    ) q;
  return_payload := jsonb_build_object('created_at', p_created_at, 'refund_mode', 'MONEY',
    'refund_method', coalesce(refund_method, 'CASH'), 'items', coalesce(items, '[]'::jsonb));
  perform public.storevia_return_sale(p_store_id, p_sale_id, return_request, return_payload);
  update public."sales" set status = 'CANCELLED', cancel_reason = nullif(btrim(p_reason), ''),
    cancelled_at = p_created_at where store_id = p_store_id and id = p_sale_id;
  insert into public.storevia_transaction_receipts (store_id, request_id, operation, result_id)
    values (p_store_id, p_request_id, 'CANCEL', p_sale_id);
  return p_sale_id;
end;
$$;

create or replace function public.storevia_adjust_stock(
  p_store_id text,
  p_request_id text,
  p_batch_id text,
  p_type text,
  p_qty_milli bigint,
  p_note text,
  p_created_at bigint
) returns text
language plpgsql security definer set search_path = public as $$
declare
  receipt public.storevia_transaction_receipts%rowtype;
  batch_row public."batches"%rowtype;
  signed_qty bigint;
  branch text;
begin
  if auth.uid() is null or not public.can_write(p_store_id, array['OWNER','MANAGER']) then
    raise exception 'cloud stock adjustment write access required';
  end if;
  if p_type not in ('DAMAGE','EXPIRED','ADJUSTMENT_IN','ADJUSTMENT_OUT') then raise exception 'invalid stock adjustment type'; end if;
  if p_qty_milli is null or p_qty_milli <= 0 then raise exception 'quantity must be positive'; end if;
  select * into receipt from public.storevia_transaction_receipts
    where store_id = p_store_id and request_id = p_request_id;
  if found then
    if receipt.operation <> 'STOCK_ADJUST' then raise exception 'request id was already used for another operation'; end if;
    return receipt.result_id;
  end if;
  select * into batch_row from public."batches"
    where store_id = p_store_id and id = p_batch_id and deleted_at is null for update;
  if not found then raise exception 'batch was not found'; end if;
  signed_qty := case when p_type = 'ADJUSTMENT_IN' then p_qty_milli else -p_qty_milli end;
  if signed_qty < 0 and batch_row.qty_on_hand_milli < p_qty_milli then raise exception 'adjustment exceeds on-hand stock'; end if;
  update public."batches" set qty_on_hand_milli = qty_on_hand_milli + signed_qty,
    updated_at = p_created_at where store_id = p_store_id and id = p_batch_id;
  branch := batch_row.branch_id;
  insert into public."stock_movements" (
    id, product_id, branch_id, batch_id, type, qty_milli, unit_cost_paise,
    ref_type, ref_id, reason, created_at, store_id
  ) values (gen_random_uuid()::text, batch_row.product_id, branch, p_batch_id, p_type,
    signed_qty, batch_row.cost_paise, 'MANUAL', p_batch_id, nullif(btrim(p_note), ''), p_created_at, p_store_id);
  insert into public.storevia_transaction_receipts (store_id, request_id, operation, result_id)
    values (p_store_id, p_request_id, 'STOCK_ADJUST', p_batch_id);
  return p_batch_id;
end;
$$;

create or replace function public.storevia_quote_sale(
  p_store_id text,
  p_request_id text,
  p_payload jsonb
) returns jsonb
language plpgsql security definer set search_path = public as $$
declare
  sale_id text;
  quote jsonb;
  error_detail text;
begin
  begin
    sale_id := public.storevia_checkout(p_store_id, p_request_id, p_payload);
    select jsonb_build_object(
      'subtotal_paise', s.subtotal_paise,
      'discount_paise', s.discount_paise,
      'tax_paise', s.tax_paise,
      'round_off_paise', s.round_off_paise,
      'total_paise', s.total_paise,
      'sold_against_negative_stock', exists (
        select 1 from public."stock_movements" sm
        where sm.store_id = p_store_id and sm.ref_type = 'SALE' and sm.ref_id = sale_id and sm.batch_id is null
      )
    ) into quote
    from public."sales" s where s.store_id = p_store_id and s.id = sale_id;
    raise exception using errcode = 'PZ001', message = 'quote complete', detail = quote::text;
  exception when sqlstate 'PZ001' then
    get stacked diagnostics error_detail = pg_exception_detail;
    return error_detail::jsonb;
  end;
end;
$$;

create or replace function public.storevia_quote_return(
  p_store_id text,
  p_sale_id text,
  p_items jsonb
) returns jsonb
language plpgsql security definer set search_path = public as $$
declare
  sale_row public."sales"%rowtype;
  item jsonb;
  sale_line record;
  wanted bigint;
  remaining bigint;
  take_qty bigint;
  amount bigint;
  total bigint := 0;
  due_reduced bigint;
begin
  if auth.uid() is null or not public.can_write(p_store_id, array['OWNER','MANAGER']) then
    raise exception 'cloud return read access required';
  end if;
  select * into sale_row from public."sales"
    where store_id = p_store_id and id = p_sale_id;
  if not found then raise exception 'sale was not found'; end if;
  if sale_row.status = 'CANCELLED' then raise exception 'sale is cancelled'; end if;
  if jsonb_typeof(p_items) <> 'array' or jsonb_array_length(p_items) = 0 then raise exception 'nothing to return'; end if;
  for item in select value from jsonb_array_elements(p_items) loop
    wanted := (item->>'qty_milli')::bigint;
    if wanted <= 0 then raise exception 'return quantity must be positive'; end if;
    remaining := wanted;
    for sale_line in select * from public."sale_lines"
      where store_id = p_store_id and sale_id = p_sale_id and product_id = item->>'product_id'
        and returned_qty_milli < qty_milli order by server_updated_at, id
    loop
      exit when remaining = 0;
      take_qty := least(remaining, sale_line.qty_milli - sale_line.returned_qty_milli);
      amount := case when take_qty = sale_line.qty_milli - sale_line.returned_qty_milli
        then sale_line.line_total_paise - sale_line.returned_amount_paise
        else public.storevia_round_half_up(sale_line.line_total_paise::numeric * take_qty / sale_line.qty_milli) end;
      total := total + amount;
      remaining := remaining - take_qty;
    end loop;
    if remaining > 0 then raise exception 'return quantity exceeds remaining sold quantity'; end if;
  end loop;
  if total = 0 then raise exception 'nothing to return'; end if;
  due_reduced := case when sale_row.customer_id is null then 0
    else least(total, greatest(sale_row.total_paise - sale_row.returned_paise - sale_row.paid_paise, 0)) end;
  return jsonb_build_object(
    'total_paise', total,
    'due_reduced_paise', due_reduced,
    'refund_paise', total - due_reduced
  );
end;
$$;

revoke all on function public.storevia_round_half_up(numeric) from public, anon, authenticated;
revoke all on function public.storevia_checkout(text,text,jsonb) from public, anon, authenticated;
revoke all on function public.storevia_return_sale(text,text,text,jsonb) from public, anon, authenticated;
revoke all on function public.storevia_cancel_sale(text,text,text,text,bigint) from public, anon, authenticated;
revoke all on function public.storevia_adjust_stock(text,text,text,text,bigint,text,bigint) from public, anon, authenticated;
revoke all on function public.storevia_quote_sale(text,text,jsonb) from public, anon, authenticated;
revoke all on function public.storevia_quote_return(text,text,jsonb) from public, anon, authenticated;
grant execute on function public.storevia_checkout(text,text,jsonb) to authenticated;
grant execute on function public.storevia_return_sale(text,text,text,jsonb) to authenticated;
grant execute on function public.storevia_cancel_sale(text,text,text,text,bigint) to authenticated;
grant execute on function public.storevia_adjust_stock(text,text,text,text,bigint,text,bigint) to authenticated;
grant execute on function public.storevia_quote_sale(text,text,jsonb) to authenticated;
grant execute on function public.storevia_quote_return(text,text,jsonb) to authenticated;
