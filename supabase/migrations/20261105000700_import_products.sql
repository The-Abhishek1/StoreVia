create or replace function public.storevia_import_products(
  p_store_id text,
  p_rows jsonb,
  p_policy text,
  p_branch_id text default null
) returns jsonb
language plpgsql
security invoker
set search_path = public, pg_temp
as $$
declare
  row_data jsonb;
  existing_id text;
  barcode_owner text;
  product_id text;
  batch_id text;
  now_ms bigint;
  row_name text;
  row_sku text;
  row_barcode text;
  row_unit_id text;
  row_opening_qty bigint;
  row_expiry text;
  created_count integer := 0;
  updated_count integer := 0;
  skipped_count integer := 0;
begin
  if not public.can_write(p_store_id, array['OWNER', 'MANAGER']) then
    raise exception 'You do not have permission to import products into this store.';
  end if;
  if p_policy not in ('SKIP', 'UPDATE') then
    raise exception 'Unknown duplicate policy.';
  end if;
  if jsonb_typeof(p_rows) <> 'array' or jsonb_array_length(p_rows) = 0 then
    raise exception 'No valid product rows were provided.';
  end if;

  perform pg_advisory_xact_lock(hashtextextended(p_store_id, 0));
  now_ms := (extract(epoch from clock_timestamp()) * 1000)::bigint;

  for row_data in select value from jsonb_array_elements(p_rows)
  loop
    row_name := btrim(coalesce(row_data->>'name', ''));
    row_sku := nullif(btrim(coalesce(row_data->>'sku', '')), '');
    row_barcode := nullif(btrim(coalesce(row_data->>'barcode', '')), '');
    row_unit_id := coalesce(row_data->>'unit_id', '');
    row_opening_qty := coalesce((row_data->>'opening_qty_milli')::bigint, 0);

    if row_name = '' then raise exception 'A product name is required.'; end if;
    if (row_data->>'price_paise')::bigint < 0 or (row_data->>'cost_paise')::bigint < 0 then
      raise exception 'Product price and cost must not be negative.';
    end if;
    if coalesce((row_data->>'gst_rate_bp')::integer, -1) not between 0 and 10000 then
      raise exception 'Product GST rate is invalid.';
    end if;
    if coalesce((row_data->>'min_stock_milli')::bigint, -1) < 0 or row_opening_qty < 0 then
      raise exception 'Product quantities must not be negative.';
    end if;
    if jsonb_typeof(row_data->'tax_inclusive') is distinct from 'boolean' then
      raise exception 'Product tax flag is invalid.';
    end if;
    if row_data->>'attributes_json' is not null and (
      jsonb_typeof(row_data->'attributes_json') is distinct from 'string'
      or jsonb_typeof((row_data->>'attributes_json')::jsonb) is distinct from 'object'
    ) then
      raise exception 'Product attributes must be a JSON object.';
    end if;
    if not exists (
      select 1 from public."units" u where u.store_id = p_store_id and u."id" = row_unit_id
    ) then
      raise exception 'A product unit is no longer available in this store.';
    end if;

    row_expiry := nullif(row_data->>'expiry', '');
    if row_expiry is not null then
      perform row_expiry::date;
    end if;

    existing_id := null;
    select p."id" into existing_id
    from public."products" p
    where p.store_id = p_store_id
      and p."deleted_at" is null
      and (
        (row_barcode is not null and exists (
          select 1 from public."product_barcodes" bc
          where bc.store_id = p_store_id and bc."product_id" = p."id"
            and bc."barcode" = row_barcode and bc."deleted_at" is null
        ))
        or (row_sku is not null and p."sku" = row_sku)
        or (row_barcode is null and row_sku is null and lower(p."name") = lower(row_name))
      )
    order by case
      when row_barcode is not null and exists (
        select 1 from public."product_barcodes" bc
        where bc.store_id = p_store_id and bc."product_id" = p."id"
          and bc."barcode" = row_barcode and bc."deleted_at" is null
      ) then 0
      when row_sku is not null and p."sku" = row_sku then 1
      else 2
    end, p."id"
    limit 1;

    if row_barcode is not null then
      select bc."product_id" into barcode_owner
      from public."product_barcodes" bc
      join public."products" p on p."id" = bc."product_id" and p.store_id = bc.store_id
      where bc.store_id = p_store_id and bc."barcode" = row_barcode
        and bc."deleted_at" is null and p."deleted_at" is null
      order by bc."id" limit 1;
    else
      barcode_owner := null;
    end if;

    if existing_id is not null then
      if p_policy = 'SKIP' or (barcode_owner is not null and barcode_owner <> existing_id) then
        skipped_count := skipped_count + 1;
        continue;
      end if;
      update public."products"
      set "sku" = coalesce(row_sku, "sku"),
          "hsn_code" = coalesce(nullif(row_data->>'hsn', ''), "hsn_code"),
          "gst_rate_bp" = (row_data->>'gst_rate_bp')::integer,
          "tax_inclusive" = case when (row_data->>'tax_inclusive')::boolean then 1 else 0 end,
          "cost_paise" = (row_data->>'cost_paise')::bigint,
          "sell_price_paise" = (row_data->>'price_paise')::bigint,
          "mrp_paise" = coalesce(nullif(row_data->>'mrp_paise', '')::bigint, "mrp_paise"),
          "min_stock_milli" = (row_data->>'min_stock_milli')::bigint,
          "track_expiry" = case when row_expiry is not null then 1 else "track_expiry" end,
          "attributes_json" = coalesce(nullif(row_data->>'attributes_json', ''), "attributes_json"),
          "updated_at" = now_ms
      where store_id = p_store_id and "id" = existing_id;
      if row_barcode is not null and not exists (
        select 1 from public."product_barcodes" bc
        where bc.store_id = p_store_id and bc."product_id" = existing_id and bc."deleted_at" is null
      ) then
        insert into public."product_barcodes" (
          "id", "product_id", "barcode", "updated_at", "deleted_at", store_id
        ) values (gen_random_uuid()::text, existing_id, row_barcode, now_ms, null, p_store_id);
      end if;
      updated_count := updated_count + 1;
      continue;
    end if;

    if barcode_owner is not null then
      skipped_count := skipped_count + 1;
      continue;
    end if;
    if row_opening_qty > 0 and (
      p_branch_id is null or not exists (
        select 1 from public."branches" b where b.store_id = p_store_id
          and b."id" = p_branch_id and b."is_active" <> 0 and b."deleted_at" is null
      )
    ) then
      raise exception 'Opening stock requires a valid active cloud branch.';
    end if;

    product_id := gen_random_uuid()::text;
    insert into public."products" (
      "id", "name", "sku", "unit_id", "hsn_code", "gst_rate_bp", "tax_inclusive",
      "mrp_paise", "sell_price_paise", "cost_paise", "track_expiry", "serial_tracked",
      "min_stock_milli", "is_active", "attributes_json", "created_at", "updated_at",
      "deleted_at", store_id
    ) values (
      product_id, row_name, row_sku, row_unit_id, nullif(row_data->>'hsn', ''),
      (row_data->>'gst_rate_bp')::integer,
      case when (row_data->>'tax_inclusive')::boolean then 1 else 0 end,
      nullif(row_data->>'mrp_paise', '')::bigint,
      (row_data->>'price_paise')::bigint, (row_data->>'cost_paise')::bigint,
      case when row_expiry is not null then 1 else 0 end, 0,
      (row_data->>'min_stock_milli')::bigint, 1,
      coalesce(nullif(row_data->>'attributes_json', ''), '{}'),
      now_ms, now_ms, null, p_store_id
    );
    if row_barcode is not null then
      insert into public."product_barcodes" (
        "id", "product_id", "barcode", "updated_at", "deleted_at", store_id
      ) values (gen_random_uuid()::text, product_id, row_barcode, now_ms, null, p_store_id);
    end if;
    if row_opening_qty > 0 then
      batch_id := gen_random_uuid()::text;
      insert into public."batches" (
        "id", "product_id", "branch_id", "batch_no", "expiry_date", "cost_paise",
        "mrp_paise", "qty_received_milli", "qty_on_hand_milli", "received_at",
        "updated_at", "deleted_at", store_id
      ) values (
        batch_id, product_id, p_branch_id, nullif(row_data->>'batch_no', ''), row_expiry,
        (row_data->>'cost_paise')::bigint, nullif(row_data->>'mrp_paise', '')::bigint,
        row_opening_qty, row_opening_qty, now_ms, now_ms, null, p_store_id
      );
      insert into public."stock_movements" (
        "id", "product_id", "branch_id", "batch_id", "type", "qty_milli",
        "unit_cost_paise", "ref_type", "ref_id", "reason", "created_at", store_id
      ) values (
        gen_random_uuid()::text, product_id, p_branch_id, batch_id, 'OPENING',
        row_opening_qty, (row_data->>'cost_paise')::bigint, 'IMPORT', batch_id, null,
        now_ms, p_store_id
      );
    end if;
    created_count := created_count + 1;
  end loop;

  return jsonb_build_object(
    'created', created_count,
    'updated', updated_count,
    'skipped', skipped_count
  );
end;
$$;
