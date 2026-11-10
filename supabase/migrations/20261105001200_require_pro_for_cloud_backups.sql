create or replace function public.has_active_pro(sid text) returns boolean
language sql stable security definer set search_path = public as $$
  select public.is_member(sid) and exists (
    select 1
    from public.subscriptions s
    where s.store_id = sid
      and s.plan = 'pro'
      and s.status = 'active'
      and s.expires_at > now()
  );
$$;

revoke all on function public.has_active_pro(text) from public, anon;
grant execute on function public.has_active_pro(text) to authenticated;

do $$
begin
  if to_regprocedure('public.storevia_import_products(text,jsonb,text,text)') is not null
     and to_regprocedure('public.storevia_import_products_unchecked(text,jsonb,text,text)') is null then
    alter function public.storevia_import_products(text,jsonb,text,text)
      rename to storevia_import_products_unchecked;
  end if;
end;
$$;

revoke all on function public.storevia_import_products_unchecked(text,jsonb,text,text)
  from public, anon, authenticated;

create or replace function public.storevia_import_products(
  p_store_id text,
  p_rows jsonb,
  p_policy text,
  p_branch_id text default null
) returns jsonb
language plpgsql
security definer
set search_path = public, pg_temp
as $$
begin
  if not public.has_active_pro(p_store_id) then
    raise exception 'Cloud product import requires an active Pro subscription.';
  end if;

  return public.storevia_import_products_unchecked(p_store_id, p_rows, p_policy, p_branch_id);
end;
$$;

revoke all on function public.storevia_import_products(text,jsonb,text,text) from public, anon;
grant execute on function public.storevia_import_products(text,jsonb,text,text) to authenticated;

drop policy if exists "store members read backups" on storage.objects;
create policy "store members read backups" on storage.objects
for select using (
  bucket_id = 'storevia-backups'
  and public.has_active_pro((storage.foldername(name))[1])
);

drop policy if exists "store managers upload backups" on storage.objects;
create policy "store managers upload backups" on storage.objects
for insert with check (
  bucket_id = 'storevia-backups'
  and public.has_active_pro((storage.foldername(name))[1])
  and public.can_write((storage.foldername(name))[1], array['OWNER','MANAGER'])
);

drop policy if exists "store managers update backups" on storage.objects;
create policy "store managers update backups" on storage.objects
for update using (
  bucket_id = 'storevia-backups'
  and public.has_active_pro((storage.foldername(name))[1])
  and public.can_write((storage.foldername(name))[1], array['OWNER','MANAGER'])
) with check (
  bucket_id = 'storevia-backups'
  and public.has_active_pro((storage.foldername(name))[1])
  and public.can_write((storage.foldername(name))[1], array['OWNER','MANAGER'])
);
