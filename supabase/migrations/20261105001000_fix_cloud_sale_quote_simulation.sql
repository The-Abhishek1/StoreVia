create or replace function public.storevia_quote_sale(
  p_store_id text,
  p_request_id text,
  p_payload jsonb
) returns jsonb
language plpgsql security definer set search_path = public as $$
declare
  v_sale_id text;
  v_payload jsonb;
  quote jsonb;
  error_detail text;
begin
  v_payload := jsonb_set(coalesce(p_payload, '{}'::jsonb), '{quote}', 'true'::jsonb, true);
  begin
    v_sale_id := public.storevia_checkout(p_store_id, p_request_id, v_payload);
    select jsonb_build_object(
      'subtotal_paise', s.subtotal_paise,
      'discount_paise', s.discount_paise,
      'tax_paise', s.tax_paise,
      'round_off_paise', s.round_off_paise,
      'total_paise', s.total_paise,
      'sold_against_negative_stock', exists (
        select 1 from public."stock_movements" sm
        where sm.store_id = p_store_id and sm.ref_type = 'SALE' and sm.ref_id = v_sale_id and sm.batch_id is null
      )
    ) into quote
    from public."sales" s where s.store_id = p_store_id and s.id = v_sale_id;
    raise exception using errcode = 'PZ001', message = 'quote complete', detail = quote::text;
  exception when sqlstate 'PZ001' then
    get stacked diagnostics error_detail = pg_exception_detail;
    return error_detail::jsonb;
  end;
end;
$$;

revoke all on function public.storevia_quote_sale(text,text,jsonb) from public, anon, authenticated;
grant execute on function public.storevia_quote_sale(text,text,jsonb) to authenticated;

notify pgrst, 'reload schema';
