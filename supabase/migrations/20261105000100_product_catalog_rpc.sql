create or replace function public.save_product_catalog(
  p_store_id text,
  p_product jsonb,
  p_barcode text,
  p_barcode_id text,
  p_now bigint
) returns jsonb
language plpgsql
security invoker
set search_path = public, pg_temp
as $$
declare
  v_product public.products%rowtype;
  v_duplicate_name text;
  v_existing_barcode text;
  v_rows integer;
begin
  if not public.can_write(p_store_id, array['OWNER', 'MANAGER']) then
    raise exception 'not authorized to edit products';
  end if;

  v_product := jsonb_populate_record(null::public.products, p_product);
  if v_product.id is null or v_product.store_id is distinct from p_store_id then
    raise exception 'product store does not match the selected store';
  end if;
  if not exists (
    select 1 from public.units unit
    where unit.id = v_product.unit_id and unit.store_id = p_store_id
  ) then
    raise exception 'unit was not found in the selected store';
  end if;
  if v_product.category_id is not null and not exists (
    select 1 from public.categories category
    where category.id = v_product.category_id
      and category.store_id = p_store_id
      and category.deleted_at is null
  ) then
    raise exception 'category was not found in the selected store';
  end if;

  if v_product.parent_product_id is not null then
    if not exists (
      select 1
      from public.products parent
      where parent.id = v_product.parent_product_id
        and parent.store_id = p_store_id
        and parent.deleted_at is null
        and parent.parent_product_id is null
        and parent.is_active = 1
    ) or exists (
      select 1
      from public.stock_movements movement
      where movement.store_id = p_store_id
        and movement.product_id = v_product.parent_product_id
    ) then
      return jsonb_build_object('status', 'invalid_variant_parent');
    end if;
  end if;

  p_barcode := nullif(btrim(p_barcode), '');
  if p_barcode is not null then
    perform pg_advisory_xact_lock(hashtext(p_store_id), hashtext(p_barcode));
    select product.name
      into v_duplicate_name
    from public.product_barcodes barcode
    left join public.products product
      on product.id = barcode.product_id and product.store_id = barcode.store_id
    where barcode.store_id = p_store_id
      and barcode.barcode = p_barcode
      and barcode.deleted_at is null
      and barcode.product_id <> v_product.id
    order by barcode.updated_at desc, barcode.id
    limit 1
    for update of barcode;

    if found then
      return jsonb_build_object(
        'status', 'duplicate_barcode',
        'product_name', coalesce(v_duplicate_name, 'another product')
      );
    end if;
  end if;

  insert into public.products (
    id, name, parent_product_id, variant_label, sku, category_id, unit_id, hsn_code,
    gst_rate_bp, tax_inclusive, mrp_paise, sell_price_paise, cost_paise, track_expiry,
    serial_tracked, min_stock_milli, is_active, attributes_json, created_at, updated_at,
    deleted_at, store_id
  ) values (
    v_product.id, v_product.name, v_product.parent_product_id, v_product.variant_label,
    v_product.sku, v_product.category_id, v_product.unit_id, v_product.hsn_code,
    v_product.gst_rate_bp, v_product.tax_inclusive, v_product.mrp_paise,
    v_product.sell_price_paise, v_product.cost_paise, v_product.track_expiry,
    v_product.serial_tracked, v_product.min_stock_milli, v_product.is_active,
    v_product.attributes_json, v_product.created_at, p_now, v_product.deleted_at, p_store_id
  )
  on conflict (id) do update set
    name = excluded.name,
    parent_product_id = excluded.parent_product_id,
    variant_label = excluded.variant_label,
    sku = excluded.sku,
    category_id = excluded.category_id,
    unit_id = excluded.unit_id,
    hsn_code = excluded.hsn_code,
    gst_rate_bp = excluded.gst_rate_bp,
    tax_inclusive = excluded.tax_inclusive,
    mrp_paise = excluded.mrp_paise,
    sell_price_paise = excluded.sell_price_paise,
    cost_paise = excluded.cost_paise,
    track_expiry = excluded.track_expiry,
    serial_tracked = excluded.serial_tracked,
    min_stock_milli = excluded.min_stock_milli,
    is_active = excluded.is_active,
    attributes_json = excluded.attributes_json,
    updated_at = excluded.updated_at,
    deleted_at = excluded.deleted_at
  where public.products.store_id = p_store_id;

  get diagnostics v_rows = row_count;
  if v_rows = 0 then
    raise exception 'product was not found in the selected store';
  end if;

  select barcode.barcode
    into v_existing_barcode
  from public.product_barcodes barcode
  where barcode.store_id = p_store_id
    and barcode.product_id = v_product.id
    and barcode.deleted_at is null
  order by barcode.updated_at desc, barcode.id
  limit 1;

  if v_existing_barcode is distinct from p_barcode then
    update public.product_barcodes
       set deleted_at = p_now, updated_at = p_now
     where store_id = p_store_id
       and product_id = v_product.id
       and deleted_at is null;
    if p_barcode is not null then
      if p_barcode_id is null then
        raise exception 'barcode ID is required';
      end if;
      insert into public.product_barcodes (id, product_id, barcode, updated_at, deleted_at, store_id)
      values (p_barcode_id, v_product.id, p_barcode, p_now, null, p_store_id);
    end if;
  end if;

  if v_product.parent_product_id is not null then
    update public.product_barcodes
       set deleted_at = p_now, updated_at = p_now
     where store_id = p_store_id
       and product_id = v_product.parent_product_id
       and deleted_at is null;
  end if;

  return jsonb_build_object('status', 'saved', 'id', v_product.id);
end;
$$;

create or replace function public.archive_product_catalog(
  p_store_id text,
  p_product_id text,
  p_now bigint
) returns void
language plpgsql
security invoker
set search_path = public, pg_temp
as $$
declare
  v_parent_product_id text;
begin
  if not public.can_write(p_store_id, array['OWNER', 'MANAGER']) then
    raise exception 'not authorized to edit products';
  end if;

  select product.parent_product_id
    into v_parent_product_id
  from public.products product
  where product.store_id = p_store_id
    and product.id = p_product_id
    and product.deleted_at is null
  for update;
  if not found then
    raise exception 'product was not found in the selected store';
  end if;

  if v_parent_product_id is null then
    update public.products
       set deleted_at = p_now, updated_at = p_now
     where store_id = p_store_id
       and parent_product_id = p_product_id
       and deleted_at is null;
    update public.product_barcodes
       set deleted_at = p_now, updated_at = p_now
     where store_id = p_store_id
       and deleted_at is null
       and (product_id = p_product_id or product_id in (
         select id from public.products where store_id = p_store_id and parent_product_id = p_product_id
       ));
  else
    update public.product_barcodes
       set deleted_at = p_now, updated_at = p_now
     where store_id = p_store_id and product_id = p_product_id and deleted_at is null;
  end if;

  update public.products
     set deleted_at = p_now, updated_at = p_now
   where store_id = p_store_id and id = p_product_id;
end;
$$;

create or replace function public.delete_category_catalog(
  p_store_id text,
  p_category_id text,
  p_now bigint
) returns void
language plpgsql
security invoker
set search_path = public, pg_temp
as $$
begin
  if not public.can_write(p_store_id, array['OWNER', 'MANAGER']) then
    raise exception 'not authorized to edit categories';
  end if;

  update public.products
     set category_id = null, updated_at = p_now
   where store_id = p_store_id and category_id = p_category_id and deleted_at is null;

  update public.categories
     set deleted_at = p_now, updated_at = p_now
   where store_id = p_store_id and id = p_category_id and deleted_at is null;
  if not found then
    raise exception 'category was not found in the selected store';
  end if;
end;
$$;

revoke all on function public.save_product_catalog(text, jsonb, text, text, bigint) from public;
grant execute on function public.save_product_catalog(text, jsonb, text, text, bigint) to authenticated;
revoke all on function public.archive_product_catalog(text, text, bigint) from public;
grant execute on function public.archive_product_catalog(text, text, bigint) to authenticated;
revoke all on function public.delete_category_catalog(text, text, bigint) from public;
grant execute on function public.delete_category_catalog(text, text, bigint) to authenticated;
