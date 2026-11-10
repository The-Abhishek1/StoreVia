create table if not exists public.storevia_sales_import_receipts (
  store_id text not null,
  request_id text not null,
  imported_count integer not null,
  created_at timestamptz not null default now(),
  primary key (store_id, request_id)
);

alter table public.storevia_sales_import_receipts enable row level security;
revoke all on public.storevia_sales_import_receipts from public, anon, authenticated;

create or replace function public.storevia_import_sales(
  p_store_id text,
  p_request_id text,
  p_document jsonb
) returns jsonb
language plpgsql
security definer
set search_path = public, pg_temp
as $$
#variable_conflict use_variable
declare
  receipt public.storevia_sales_import_receipts%rowtype;
  summary jsonb;
  line_data jsonb;
  payment jsonb;
  sale_id text;
  branch_id text;
  customer_id text;
  product_row public."products"%rowtype;
  line_id text;
  line_qty bigint;
  line_quantity numeric;
  line_weight bigint;
  weight_total numeric;
  cumulative_weight numeric;
  allocated_total bigint;
  previous_total bigint;
  allocated_tax bigint;
  previous_tax bigint;
  allocated_discount bigint;
  previous_discount bigint;
  gross_total bigint;
  summary_total bigint;
  summary_tax bigint;
  summary_discount bigint;
  paid_total bigint;
  created_at_ms bigint;
  line_count integer;
  imported_count integer := 0;
  payment_rows jsonb;
begin
  if auth.uid() is null or not public.can_write(p_store_id, array['OWNER','MANAGER']) then
    raise exception 'Owner or manager access is required to import sales.';
  end if;
  if not public.has_active_pro(p_store_id) then
    raise exception 'Cloud sales import requires an active Pro subscription.';
  end if;
  if p_request_id is null or btrim(p_request_id) = '' then
    raise exception 'A sales import request id is required.';
  end if;
  if jsonb_typeof(p_document) is distinct from 'object'
     or jsonb_typeof(p_document->'summaries') is distinct from 'array'
     or jsonb_typeof(p_document->'lines') is distinct from 'array'
     or jsonb_array_length(p_document->'summaries') = 0
     or jsonb_array_length(p_document->'lines') = 0 then
    raise exception 'Sales import requires at least one summary and one line item.';
  end if;
  payment_rows := coalesce(p_document->'payments', '[]'::jsonb);
  if jsonb_typeof(payment_rows) is distinct from 'array' then
    raise exception 'Sales import payments must be an array.';
  end if;
  if exists (
    select 1
    from jsonb_array_elements(payment_rows) payment_row
    where payment_row->>'bill_id' is null
      or payment_row->>'bill_id' not in (
        select summary_row->>'bill_id'
        from jsonb_array_elements(p_document->'summaries') summary_row
      )
      or upper(coalesce(payment_row->>'method', '')) not in ('CASH', 'UPI', 'CARD', 'OTHER')
      or coalesce((payment_row->>'amount_paise')::bigint, 0) <= 0
  ) then
    raise exception 'A payment row has an invalid bill, method, or amount.';
  end if;

  perform pg_advisory_xact_lock(hashtextextended(p_store_id, 0));
  select * into receipt from public.storevia_sales_import_receipts
    where store_id = p_store_id and request_id = p_request_id;
  if found then
    return jsonb_build_object('imported', receipt.imported_count);
  end if;

  select id into branch_id
  from public."branches"
  where store_id = p_store_id and deleted_at is null and is_active <> 0
  order by created_at, name
  limit 1;
  if branch_id is null then raise exception 'Add an active branch before importing sales.'; end if;

  for summary in select value from jsonb_array_elements(p_document->'summaries')
  loop
    if nullif(btrim(summary->>'bill_id'), '') is null then raise exception 'A bill ID is required.'; end if;
    if exists (
      select 1 from public."sales"
      where store_id = p_store_id and invoice_no = summary->>'bill_id'
    ) then raise exception 'Bill ID % already exists.', summary->>'bill_id'; end if;

    if nullif(btrim(summary->>'branch_name'), '') is not null then
      select id into branch_id
      from public."branches"
      where store_id = p_store_id and deleted_at is null
        and lower(name) = lower(btrim(summary->>'branch_name'))
      limit 1;
      if branch_id is null then raise exception 'Branch % was not found.', summary->>'branch_name'; end if;
    else
      select id into branch_id
      from public."branches"
      where store_id = p_store_id and deleted_at is null and is_active <> 0
      order by created_at, name
      limit 1;
    end if;

    customer_id := null;
    if nullif(btrim(summary->>'customer_name'), '') is not null then
      select id into customer_id from public."customers"
      where store_id = p_store_id and deleted_at is null
        and lower(name) = lower(btrim(summary->>'customer_name'))
      order by id limit 1;
      if customer_id is null then raise exception 'Customer % was not found.', summary->>'customer_name'; end if;
    end if;

    summary_total := (summary->>'total_paise')::bigint;
    summary_tax := coalesce((summary->>'tax_paise')::bigint, 0);
    summary_discount := coalesce((summary->>'discount_paise')::bigint, 0);
    created_at_ms := (summary->>'created_at')::bigint;
    if summary_total < 0 or summary_tax < 0 or summary_tax > summary_total or summary_discount < 0 then
      raise exception 'Bill % has inconsistent totals.', summary->>'bill_id';
    end if;
    if nullif(summary->>'payment_method', '') is not null
       and upper(summary->>'payment_method') not in ('CASH', 'UPI', 'CARD', 'OTHER') then
      raise exception 'Bill % has an invalid payment method.', summary->>'bill_id';
    end if;
    if coalesce((summary->>'is_credit')::boolean, false) and customer_id is null then
      raise exception 'Credit sales require a customer.';
    end if;

    select coalesce(sum((p->>'amount_paise')::bigint), 0) into paid_total
    from jsonb_array_elements(payment_rows) p
    where p->>'bill_id' = summary->>'bill_id';
    if paid_total = 0 and not coalesce((summary->>'is_credit')::boolean, false)
       and nullif(summary->>'payment_method', '') is not null then
      paid_total := summary_total;
    end if;
    if paid_total > summary_total then raise exception 'Payments exceed bill % total.', summary->>'bill_id'; end if;
    if paid_total < summary_total and customer_id is null then
      raise exception 'Unpaid sales require a customer.';
    end if;

    select count(*), coalesce(sum(
      public.storevia_round_half_up((line->>'unit_price_paise')::numeric *
        (case when unit.allow_decimal <> 0 then
          public.storevia_round_half_up((line->>'quantity')::numeric * 1000)
        else
          public.storevia_round_half_up((line->>'quantity')::numeric) * 1000
        end) / 1000)
    ), 0)
    into line_count, weight_total
    from jsonb_array_elements(p_document->'lines') line
    join public."products" product on product.store_id = p_store_id
      and product.deleted_at is null
      and ((nullif(line->>'sku', '') is not null and product.sku = line->>'sku')
        or (nullif(line->>'sku', '') is null and lower(product.name) = lower(line->>'product_name')))
    join public."units" unit on unit.store_id = p_store_id and unit.id = product.unit_id
    where line->>'bill_id' = summary->>'bill_id';
    if line_count = 0 then raise exception 'Bill % has no matching line items.', summary->>'bill_id'; end if;
    if weight_total = 0 and summary_total > 0 then raise exception 'Bill % has no valid line value.', summary->>'bill_id'; end if;

    sale_id := gen_random_uuid()::text;
    insert into public."sales" (
      id, invoice_no, branch_id, customer_id, status, inter_state, subtotal_paise,
      discount_paise, tax_paise, round_off_paise, total_paise, paid_paise,
      returned_paise, created_at, store_id
    ) values (
      sale_id, summary->>'bill_id', branch_id, customer_id, 'COMPLETED', 0,
      summary_total - summary_tax, summary_discount, summary_tax, 0,
      summary_total, paid_total, 0, created_at_ms, p_store_id
    );

    cumulative_weight := 0;
    previous_total := 0;
    previous_tax := 0;
    previous_discount := 0;
    for line_data in
      select line.value
      from jsonb_array_elements(p_document->'lines') with ordinality line
      where line.value->>'bill_id' = summary->>'bill_id'
      order by line.ordinality
    loop
      if nullif(line_data->>'sku', '') is not null then
        select * into product_row from public."products"
        where store_id = p_store_id and deleted_at is null and sku = line_data->>'sku'
        order by id limit 1;
      else
        select * into product_row from public."products"
        where store_id = p_store_id and deleted_at is null
          and lower(name) = lower(line_data->>'product_name')
        order by id limit 1;
      end if;
      if product_row.id is null then raise exception 'A product in bill % was not found.', summary->>'bill_id'; end if;

      line_quantity := (line_data->>'quantity')::numeric;
      if line_quantity <= 0 or line_quantity * 1000 <> trunc(line_quantity * 1000) then
        raise exception 'Line quantity must be positive with at most three decimal places.';
      end if;
      if (line_data->>'unit_price_paise')::bigint < 0 then
        raise exception 'Line unit price must not be negative.';
      end if;
      if not exists (
        select 1 from public."units" where store_id = p_store_id
          and id = product_row.unit_id and allow_decimal <> 0
      ) and line_quantity <> trunc(line_quantity) then
        raise exception 'This product unit does not allow fractional quantities.';
      end if;
      line_qty := (line_quantity * 1000)::bigint;
      if line_qty <= 0 then raise exception 'Line quantity must be positive.'; end if;
      line_weight := public.storevia_round_half_up((line_data->>'unit_price_paise')::numeric * line_qty / 1000);
      cumulative_weight := cumulative_weight + line_weight;

      if weight_total = 0 then
        allocated_total := 0; allocated_tax := 0; allocated_discount := 0;
      else
        allocated_total := floor(summary_total * cumulative_weight / weight_total)::bigint - previous_total;
        allocated_tax := floor(summary_tax * cumulative_weight / weight_total)::bigint - previous_tax;
        allocated_discount := floor(summary_discount * cumulative_weight / weight_total)::bigint - previous_discount;
      end if;
      previous_total := previous_total + allocated_total;
      previous_tax := previous_tax + allocated_tax;
      previous_discount := previous_discount + allocated_discount;
      line_id := gen_random_uuid()::text;
      insert into public."sale_lines" (
        id, sale_id, product_id, batch_id, name_snapshot, hsn_snapshot, qty_milli,
        unit_price_paise, discount_paise, gst_rate_bp, tax_inclusive,
        taxable_value_paise, cgst_paise, sgst_paise, igst_paise, line_total_paise,
        cost_snapshot_paise, returned_qty_milli, returned_amount_paise, store_id
      ) values (
        line_id, sale_id, product_row.id, null, product_row.name, product_row.hsn_code,
        line_qty, (line_data->>'unit_price_paise')::bigint, allocated_discount,
        product_row.gst_rate_bp, product_row.tax_inclusive,
        allocated_total - allocated_tax, floor(allocated_tax::numeric / 2)::bigint,
        allocated_tax - floor(allocated_tax::numeric / 2)::bigint, 0,
        allocated_total, product_row.cost_paise, 0, 0, p_store_id
      );
    end loop;

    for payment in select value from jsonb_array_elements(payment_rows)
      where value->>'bill_id' = summary->>'bill_id'
    loop
      insert into public."payments" (
        id, sale_id, customer_id, method, amount_paise, reference, created_at, store_id
      ) values (
        gen_random_uuid()::text, sale_id, customer_id, payment->>'method',
        (payment->>'amount_paise')::bigint, nullif(payment->>'reference', ''),
        created_at_ms, p_store_id
      );
    end loop;
    if paid_total = summary_total and not exists (
      select 1 from jsonb_array_elements(payment_rows) p where p->>'bill_id' = summary->>'bill_id'
    ) and nullif(summary->>'payment_method', '') is not null then
      insert into public."payments" (
        id, sale_id, customer_id, method, amount_paise, created_at, store_id
      ) values (
        gen_random_uuid()::text, sale_id, customer_id, summary->>'payment_method',
        summary_total, created_at_ms, p_store_id
      );
    end if;
    if summary_total > paid_total and customer_id is not null then
      insert into public."customer_ledger" (
        id, customer_id, type, amount_paise, ref_type, ref_id, created_at, store_id
      ) values (
        gen_random_uuid()::text, customer_id, 'SALE_ON_CREDIT',
        summary_total - paid_total, 'SALE', sale_id, created_at_ms, p_store_id
      );
    end if;
    imported_count := imported_count + 1;
  end loop;

  insert into public.storevia_sales_import_receipts(store_id, request_id, imported_count)
  values (p_store_id, p_request_id, imported_count);
  return jsonb_build_object('imported', imported_count);
end;
$$;

revoke all on function public.storevia_import_sales(text, text, jsonb) from public, anon;
grant execute on function public.storevia_import_sales(text, text, jsonb) to authenticated;
