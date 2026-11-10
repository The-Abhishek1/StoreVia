-- Atomic, store-scoped purchasing and receiving operations.
-- Apply after supabase/schema.sql and the cloud sales/inventory migration.

create or replace function public.storevia_record_purchase(
  p_store_id text,
  p_request_id text,
  p_payload jsonb
) returns text
language plpgsql security definer set search_path = public as $$
#variable_conflict use_variable
declare
  receipt public.storevia_transaction_receipts%rowtype;
  purchase_id text := gen_random_uuid()::text;
  branch_id text := nullif(p_payload->>'branch_id', '');
  supplier_id text := nullif(p_payload->>'supplier_id', '');
  invoice_no text := nullif(btrim(p_payload->>'invoice_no'), '');
  purchase_date bigint := coalesce((p_payload->>'purchase_date')::bigint, (extract(epoch from clock_timestamp()) * 1000)::bigint);
  created_at bigint := coalesce((p_payload->>'created_at')::bigint, (extract(epoch from clock_timestamp()) * 1000)::bigint);
  bill_total bigint := (p_payload->>'bill_total_paise')::bigint;
  paid bigint := coalesce((p_payload->>'paid_paise')::bigint, 0);
  pay_method text := nullif(p_payload->>'pay_method', '');
  items jsonb := coalesce(p_payload->'items', '[]'::jsonb);
  item jsonb;
  product_row public."products"%rowtype;
  batch_id text;
  line_id text;
  qty bigint;
  cost bigint;
  line_total bigint;
  calculated_total bigint := 0;
  serials jsonb;
  serial text;
begin
  if p_store_id is null or p_request_id is null or not public.can_write(p_store_id, array['OWNER','MANAGER']) then
    raise exception 'Purchase access denied';
  end if;
  select * into receipt from public.storevia_transaction_receipts
    where store_id = p_store_id and request_id = p_request_id and operation = 'record_purchase';
  if found then return receipt.result_id; end if;

  if jsonb_typeof(items) <> 'array' or jsonb_array_length(items) = 0 then
    raise exception 'Purchase must contain at least one line';
  end if;
  if exists (
    select 1 from jsonb_array_elements(items) as line(value)
      cross join lateral jsonb_array_elements_text(coalesce(line.value->'serial_numbers', '[]'::jsonb)) as serial(value)
    group by serial.value having count(*) > 1
  ) then raise exception 'Duplicate serial number in purchase'; end if;
  if branch_id is null or not exists (
    select 1 from public."branches" b where b.id = branch_id and b.store_id = p_store_id
      and b.is_active <> 0 and b.deleted_at is null
  ) then raise exception 'Purchase branch is not active in this store'; end if;
  if supplier_id is not null and not exists (
    select 1 from public."suppliers" s where s.id = supplier_id and s.store_id = p_store_id
      and s.deleted_at is null
  ) then raise exception 'Supplier does not belong to this store'; end if;

  for item in select value from jsonb_array_elements(items) loop
    select * into product_row from public."products" p
      where p.id = item->>'product_id' and p.store_id = p_store_id
        and p.deleted_at is null and p.is_active <> 0;
    if not found then raise exception 'Purchase product does not belong to this store'; end if;
    qty := (item->>'qty_milli')::bigint;
    cost := (item->>'unit_cost_paise')::bigint;
    if qty <= 0 or cost < 0 then raise exception 'Invalid purchase quantity or unit cost'; end if;
    if product_row.track_expiry <> 0 and nullif(item->>'expiry_date', '') is null then
      raise exception 'Expiry date is required for this product';
    end if;
    serials := coalesce(item->'serial_numbers', '[]'::jsonb);
    if product_row.serial_tracked <> 0 then
      if qty % 1000 <> 0 or jsonb_typeof(serials) <> 'array' or jsonb_array_length(serials) <> qty / 1000 then
        raise exception 'Serial count does not match purchase quantity';
      end if;
      if (select count(distinct value) from jsonb_array_elements_text(serials)) <> jsonb_array_length(serials)
        or exists (
          select 1 from public."serial_numbers" sn
          where sn.store_id = p_store_id and sn.deleted_at is null
            and sn.serial_number in (select value from jsonb_array_elements_text(serials))
        ) then raise exception 'Duplicate serial number'; end if;
    elsif jsonb_array_length(serials) > 0 then
      raise exception 'Serial numbers supplied for an untracked product';
    end if;
    calculated_total := calculated_total + floor((cost::numeric * qty::numeric + 500) / 1000)::bigint;
  end loop;

  bill_total := coalesce(bill_total, calculated_total);
  if bill_total < 0 or paid < 0 or paid > bill_total then raise exception 'Invalid purchase total or payment'; end if;
  if supplier_id is null and paid <> bill_total then raise exception 'Supplier is required for credit purchases'; end if;

  insert into public."purchases" (
    id, branch_id, supplier_id, invoice_no, purchase_date, total_paise, paid_paise,
    pay_method, notes, created_at, store_id
  ) values (
    purchase_id, branch_id, supplier_id, invoice_no, purchase_date, bill_total, paid,
    case when paid > 0 then coalesce(pay_method, 'CASH') else null end,
    nullif(btrim(p_payload->>'notes'), ''), created_at, p_store_id
  );

  for item in select value from jsonb_array_elements(items) loop
    select * into product_row from public."products" p
      where p.id = item->>'product_id' and p.store_id = p_store_id;
    qty := (item->>'qty_milli')::bigint;
    cost := (item->>'unit_cost_paise')::bigint;
    line_total := floor((cost::numeric * qty::numeric + 500) / 1000)::bigint;
    batch_id := gen_random_uuid()::text;
    line_id := gen_random_uuid()::text;
    insert into public."batches" (
      id, product_id, branch_id, batch_no, expiry_date, cost_paise, mrp_paise,
      qty_received_milli, qty_on_hand_milli, received_at, updated_at, deleted_at, store_id
    ) values (
      batch_id, product_row.id, branch_id, nullif(btrim(item->>'batch_no'), ''),
      nullif(item->>'expiry_date', ''), cost, nullif(item->>'mrp_paise', '')::bigint,
      qty, qty, purchase_date, created_at, null, p_store_id
    );
    insert into public."purchase_lines" (
      id, purchase_id, product_id, batch_id, name_snapshot, qty_milli, unit_cost_paise, line_total_paise, store_id
    ) values (
      line_id, purchase_id, product_row.id, batch_id,
      concat_ws(' · ', product_row.name, nullif(product_row.variant_label, '')), qty, cost, line_total, p_store_id
    );
    insert into public."stock_movements" (
      id, product_id, branch_id, batch_id, type, qty_milli, unit_cost_paise, ref_type, ref_id, created_at, store_id
    ) values (
      gen_random_uuid()::text, product_row.id, branch_id, batch_id, 'PURCHASE', qty, cost,
      'PURCHASE', purchase_id, created_at, p_store_id
    );
    serials := coalesce(item->'serial_numbers', '[]'::jsonb);
    for serial in select value from jsonb_array_elements_text(serials) loop
      insert into public."serial_numbers" (
        id, product_id, batch_id, serial_number, status, sale_line_id, updated_at, deleted_at, store_id
      ) values (
        gen_random_uuid()::text, product_row.id, batch_id, btrim(serial), 'AVAILABLE', null, created_at, null, p_store_id
      );
    end loop;
    if coalesce((p_payload->>'update_product_cost')::boolean, true) then
      update public."products" set cost_paise = cost, updated_at = created_at
        where id = product_row.id and store_id = p_store_id;
    end if;
  end loop;

  if supplier_id is not null then
    insert into public."supplier_ledger" (
      id, supplier_id, type, amount_paise, ref_type, ref_id, created_at, store_id
    ) values (
      gen_random_uuid()::text, supplier_id, 'PURCHASE', bill_total, 'PURCHASE', purchase_id, created_at, p_store_id
    );
    if paid > 0 then
      insert into public."supplier_ledger" (
        id, supplier_id, type, amount_paise, method, ref_type, ref_id, created_at, store_id
      ) values (
        gen_random_uuid()::text, supplier_id, 'PAYMENT', -paid, coalesce(pay_method, 'CASH'),
        'PURCHASE', purchase_id, created_at, p_store_id
      );
    end if;
  end if;
  insert into public.storevia_transaction_receipts(store_id, request_id, operation, result_id)
    values (p_store_id, p_request_id, 'record_purchase', purchase_id);
  return purchase_id;
end;
$$;

create or replace function public.storevia_edit_purchase(
  p_store_id text,
  p_request_id text,
  p_purchase_id text,
  p_payload jsonb
) returns text
language plpgsql security definer set search_path = public as $$
#variable_conflict use_variable
declare
  receipt public.storevia_transaction_receipts%rowtype;
  purchase_row public."purchases"%rowtype;
  new_total bigint := (p_payload->>'total_paise')::bigint;
  adjustment bigint;
  now_ms bigint := coalesce((p_payload->>'updated_at')::bigint, (extract(epoch from clock_timestamp()) * 1000)::bigint);
begin
  if p_store_id is null or not public.can_write(p_store_id, array['OWNER','MANAGER']) then
    raise exception 'Purchase access denied';
  end if;
  select * into receipt from public.storevia_transaction_receipts
    where store_id = p_store_id and request_id = p_request_id and operation = 'edit_purchase';
  if found then return receipt.result_id; end if;
  select * into purchase_row from public."purchases"
    where id = p_purchase_id and store_id = p_store_id for update;
  if not found then raise exception 'Purchase not found in this store'; end if;
  if new_total < purchase_row.paid_paise then raise exception 'New total is below the amount already paid'; end if;
  adjustment := new_total - purchase_row.total_paise;
  update public."purchases" set
    invoice_no = nullif(btrim(p_payload->>'invoice_no'), ''),
    purchase_date = coalesce((p_payload->>'purchase_date')::bigint, purchase_date),
    total_paise = new_total,
    notes = nullif(btrim(p_payload->>'notes'), '')
    where id = p_purchase_id and store_id = p_store_id;
  if purchase_row.supplier_id is not null and adjustment <> 0 then
    insert into public."supplier_ledger" (
      id, supplier_id, type, amount_paise, ref_type, ref_id, created_at, store_id
    ) values (
      gen_random_uuid()::text, purchase_row.supplier_id, 'ADJUSTMENT', adjustment,
      'PURCHASE_EDIT', p_purchase_id, now_ms, p_store_id
    );
  end if;
  insert into public.storevia_transaction_receipts(store_id, request_id, operation, result_id)
    values (p_store_id, p_request_id, 'edit_purchase', p_purchase_id);
  return p_purchase_id;
end;
$$;

create or replace function public.storevia_receive_stock(
  p_store_id text,
  p_request_id text,
  p_payload jsonb
) returns text
language plpgsql security definer set search_path = public as $$
#variable_conflict use_variable
declare
  receipt public.storevia_transaction_receipts%rowtype;
  product_row public."products"%rowtype;
  branch_id text := nullif(p_payload->>'branch_id', '');
  product_id text := nullif(p_payload->>'product_id', '');
  qty bigint := (p_payload->>'qty_milli')::bigint;
  cost bigint := coalesce((p_payload->>'cost_paise')::bigint, 0);
  created_at bigint := coalesce((p_payload->>'created_at')::bigint, (extract(epoch from clock_timestamp()) * 1000)::bigint);
  batch_id text := gen_random_uuid()::text;
  serials jsonb := coalesce(p_payload->'serial_numbers', '[]'::jsonb);
  serial text;
begin
  if p_store_id is null or not public.can_write(p_store_id, array['OWNER','MANAGER']) then
    raise exception 'Stock receiving access denied';
  end if;
  select * into receipt from public.storevia_transaction_receipts
    where store_id = p_store_id and request_id = p_request_id and operation = 'receive_stock';
  if found then return receipt.result_id; end if;
  if qty <= 0 or cost < 0 then raise exception 'Invalid stock quantity or cost'; end if;
  if branch_id is null or not exists (
    select 1 from public."branches" b where b.id = branch_id and b.store_id = p_store_id
      and b.is_active <> 0 and b.deleted_at is null
  ) then raise exception 'Receiving branch is not active in this store'; end if;
  select * into product_row from public."products" p
    where p.id = product_id and p.store_id = p_store_id and p.deleted_at is null and p.is_active <> 0;
  if not found then raise exception 'Product does not belong to this store'; end if;
  if product_row.track_expiry <> 0 and nullif(p_payload->>'expiry_date', '') is null then
    raise exception 'Expiry date is required for this product';
  end if;
  if product_row.serial_tracked <> 0 then
    if qty % 1000 <> 0 or jsonb_typeof(serials) <> 'array' or jsonb_array_length(serials) <> qty / 1000
      or (select count(distinct value) from jsonb_array_elements_text(serials)) <> jsonb_array_length(serials)
      or exists (
        select 1 from public."serial_numbers" sn
        where sn.store_id = p_store_id and sn.deleted_at is null
          and sn.serial_number in (select value from jsonb_array_elements_text(serials))
      ) then raise exception 'Invalid or duplicate serial numbers'; end if;
  elsif jsonb_array_length(serials) > 0 then raise exception 'Serial numbers supplied for an untracked product'; end if;
  insert into public."batches" (
    id, product_id, branch_id, batch_no, expiry_date, cost_paise, mrp_paise,
    qty_received_milli, qty_on_hand_milli, received_at, updated_at, deleted_at, store_id
  ) values (
    batch_id, product_id, branch_id, nullif(btrim(p_payload->>'batch_no'), ''),
    nullif(p_payload->>'expiry_date', ''), cost, nullif(p_payload->>'mrp_paise', '')::bigint,
    qty, qty, created_at, created_at, null, p_store_id
  );
  insert into public."stock_movements" (
    id, product_id, branch_id, batch_id, type, qty_milli, unit_cost_paise, ref_type, ref_id, created_at, store_id
  ) values (
    gen_random_uuid()::text, product_id, branch_id, batch_id,
    coalesce(nullif(p_payload->>'type', ''), 'PURCHASE'), qty, cost, 'MANUAL', batch_id, created_at, p_store_id
  );
  for serial in select value from jsonb_array_elements_text(serials) loop
    insert into public."serial_numbers" (
      id, product_id, batch_id, serial_number, status, sale_line_id, updated_at, deleted_at, store_id
    ) values (
      gen_random_uuid()::text, product_id, batch_id, btrim(serial), 'AVAILABLE', null, created_at, null, p_store_id
    );
  end loop;
  insert into public.storevia_transaction_receipts(store_id, request_id, operation, result_id)
    values (p_store_id, p_request_id, 'receive_stock', batch_id);
  return batch_id;
end;
$$;

create or replace function public.storevia_pay_supplier(
  p_store_id text,
  p_request_id text,
  p_payload jsonb
) returns text
language plpgsql security definer set search_path = public as $$
#variable_conflict use_variable
declare
  receipt public.storevia_transaction_receipts%rowtype;
  supplier_id text := nullif(p_payload->>'supplier_id', '');
  purchase_id text := nullif(p_payload->>'purchase_id', '');
  amount bigint := (p_payload->>'amount_paise')::bigint;
  created_at bigint := coalesce((p_payload->>'created_at')::bigint, (extract(epoch from clock_timestamp()) * 1000)::bigint);
  purchase_row public."purchases"%rowtype;
  supplier_balance bigint;
  unallocated_balance bigint;
  ledger_id text := gen_random_uuid()::text;
begin
  if p_store_id is null or not public.can_write(p_store_id, array['OWNER','MANAGER']) then
    raise exception 'Supplier payment access denied';
  end if;
  select * into receipt from public.storevia_transaction_receipts
    where store_id = p_store_id and request_id = p_request_id and operation = 'pay_supplier';
  if found then return receipt.result_id; end if;
  if amount <= 0 or not exists (
    select 1 from public."suppliers" s where s.id = supplier_id and s.store_id = p_store_id and s.deleted_at is null
  ) then raise exception 'Invalid supplier or payment amount'; end if;
  perform pg_advisory_xact_lock(hashtext(p_store_id || ':' || supplier_id));
  select coalesce(sum(amount_paise), 0) into supplier_balance from public."supplier_ledger"
    where store_id = p_store_id and "supplier_id" = supplier_id;
  if amount > supplier_balance then raise exception 'Payment exceeds supplier balance'; end if;
  if purchase_id is not null then
    select * into purchase_row from public."purchases"
      where id = purchase_id and store_id = p_store_id and "supplier_id" = supplier_id for update;
    if not found then raise exception 'Purchase does not belong to supplier'; end if;
    if amount > purchase_row.total_paise - purchase_row.paid_paise then raise exception 'Payment exceeds purchase balance'; end if;
    update public."purchases" set paid_paise = paid_paise + amount
      where id = purchase_id and store_id = p_store_id;
  else
    select supplier_balance - coalesce((
      select sum(total_paise - paid_paise) from public."purchases"
      where store_id = p_store_id and "supplier_id" = supplier_id
    ), 0) into unallocated_balance;
    if amount > unallocated_balance then raise exception 'Payment exceeds unallocated supplier balance'; end if;
  end if;
  insert into public."supplier_ledger" (
    id, supplier_id, type, amount_paise, method, ref_type, ref_id, created_at, store_id
  ) values (
    ledger_id, supplier_id, 'PAYMENT', -amount, nullif(p_payload->>'method', ''),
    case when purchase_id is null then 'SUPPLIER_BALANCE' else 'PURCHASE' end,
    purchase_id, created_at, p_store_id
  );
  insert into public.storevia_transaction_receipts(store_id, request_id, operation, result_id)
    values (p_store_id, p_request_id, 'pay_supplier', ledger_id);
  return ledger_id;
end;
$$;

revoke all on function public.storevia_record_purchase(text,text,jsonb) from public, anon, authenticated;
revoke all on function public.storevia_edit_purchase(text,text,text,jsonb) from public, anon, authenticated;
revoke all on function public.storevia_receive_stock(text,text,jsonb) from public, anon, authenticated;
revoke all on function public.storevia_pay_supplier(text,text,jsonb) from public, anon, authenticated;
grant execute on function public.storevia_record_purchase(text,text,jsonb) to authenticated;
grant execute on function public.storevia_edit_purchase(text,text,text,jsonb) to authenticated;
grant execute on function public.storevia_receive_stock(text,text,jsonb) to authenticated;
grant execute on function public.storevia_pay_supplier(text,text,jsonb) to authenticated;
