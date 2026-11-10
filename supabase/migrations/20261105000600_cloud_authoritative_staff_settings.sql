-- Cloud staff identities are Supabase Auth users with store_members roles.
-- Local PIN hashes and local staff records are deliberately not exposed by these RPCs.

create or replace function public.storevia_list_store_members(p_store_id text)
returns table(member_user_id uuid, role text, display_name text, created_at timestamptz)
language plpgsql
stable
security definer
set search_path = public
as $$
begin
  if auth.uid() is null or not public.is_member(p_store_id) then
    raise exception 'store membership required';
  end if;

  return query
    select m.user_id, m.role, m.display_name, m.created_at
    from public.store_members m
    where m.store_id = p_store_id
    order by m.created_at, m.display_name nulls last, m.user_id;
end;
$$;

revoke all on function public.storevia_list_store_members(text) from public, anon;
grant execute on function public.storevia_list_store_members(text) to authenticated;

create or replace function public.storevia_update_store_settings(
  p_store_id text,
  p_name text,
  p_owner_name text,
  p_address text,
  p_city text,
  p_phone text,
  p_gstin text,
  p_state_code text,
  p_invoice_prefix text,
  p_allow_negative_stock integer,
  p_updated_at bigint
) returns void
language plpgsql
security definer
set search_path = public
as $$
declare
  changed integer;
begin
  if not public.can_write(p_store_id, array['OWNER']) then
    raise exception 'store owner access required';
  end if;
  if btrim(p_name) = '' or p_allow_negative_stock not in (0, 1) then
    raise exception 'invalid store settings';
  end if;

  update public."store"
  set name = btrim(p_name),
      owner_name = btrim(p_owner_name),
      address = btrim(p_address),
      city = btrim(p_city),
      phone = btrim(p_phone),
      gstin = nullif(btrim(p_gstin), ''),
      state_code = p_state_code,
      invoice_prefix = p_invoice_prefix,
      allow_negative_stock = p_allow_negative_stock,
      updated_at = p_updated_at
  where store_id = p_store_id;
  get diagnostics changed = row_count;
  if changed = 0 then
    raise exception 'cloud store not found';
  end if;

  insert into public."audit_log" (
    id, created_at, actor_id, actor_name, actor_role, action, entity_type, entity_id, summary, store_id
  )
  select gen_random_uuid()::text, p_updated_at, auth.uid()::text,
         coalesce(nullif(m.display_name, ''), 'Cloud user'), m.role,
         'SETTINGS_CHANGED', 'STORE', p_store_id, 'Store settings changed', p_store_id
  from public.store_members m
  where m.store_id = p_store_id and m.user_id = auth.uid();
end;
$$;

revoke all on function public.storevia_update_store_settings(text,text,text,text,text,text,text,text,text,integer,bigint)
  from public, anon;
grant execute on function public.storevia_update_store_settings(text,text,text,text,text,text,text,text,text,integer,bigint)
  to authenticated;

create or replace function public.storevia_add_branch(
  p_store_id text,
  p_branch_id text,
  p_name text,
  p_now bigint
) returns void
language plpgsql
security definer
set search_path = public
as $$
begin
  if not public.can_write(p_store_id, array['OWNER','MANAGER']) then
    raise exception 'store branch management access required';
  end if;
  if btrim(p_name) = '' then
    raise exception 'branch name required';
  end if;
  if exists (
    select 1 from public."branches" b
    where b.store_id = p_store_id and lower(b.name) = lower(btrim(p_name)) and b.deleted_at is null
  ) then
    raise exception 'branch name already exists';
  end if;

  insert into public."branches" (
    id, name, address, city, phone, is_active, created_at, updated_at, deleted_at, store_id
  ) values (p_branch_id, btrim(p_name), '', '', '', 1, p_now, p_now, null, p_store_id);

  insert into public."audit_log" (
    id, created_at, actor_id, actor_name, actor_role, action, entity_type, entity_id, summary, store_id
  )
  select gen_random_uuid()::text, p_now, auth.uid()::text,
         coalesce(nullif(m.display_name, ''), 'Cloud user'), m.role,
         'SETTINGS_CHANGED', 'BRANCH', p_branch_id, 'Branch added: ' || btrim(p_name), p_store_id
  from public.store_members m
  where m.store_id = p_store_id and m.user_id = auth.uid();
end;
$$;

revoke all on function public.storevia_add_branch(text,text,text,bigint) from public, anon;
grant execute on function public.storevia_add_branch(text,text,text,bigint) to authenticated;

create or replace function public.storevia_rename_branch(
  p_store_id text,
  p_branch_id text,
  p_name text,
  p_now bigint
) returns void
language plpgsql
security definer
set search_path = public
as $$
declare
  previous_name text;
begin
  if not public.can_write(p_store_id, array['OWNER','MANAGER']) then
    raise exception 'store branch management access required';
  end if;
  if btrim(p_name) = '' then
    raise exception 'branch name required';
  end if;
  select b.name into previous_name
  from public."branches" b
  where b.store_id = p_store_id and b.id = p_branch_id and b.deleted_at is null
  for update;
  if not found then
    raise exception 'cloud branch not found';
  end if;
  if exists (
    select 1 from public."branches" b
    where b.store_id = p_store_id and b.id <> p_branch_id
      and lower(b.name) = lower(btrim(p_name)) and b.deleted_at is null
  ) then
    raise exception 'branch name already exists';
  end if;

  update public."branches"
  set name = btrim(p_name), updated_at = p_now
  where store_id = p_store_id and id = p_branch_id;

  insert into public."audit_log" (
    id, created_at, actor_id, actor_name, actor_role, action, entity_type, entity_id, summary, store_id
  )
  select gen_random_uuid()::text, p_now, auth.uid()::text,
         coalesce(nullif(m.display_name, ''), 'Cloud user'), m.role,
         'SETTINGS_CHANGED', 'BRANCH', p_branch_id,
         'Branch renamed: ' || previous_name || ' -> ' || btrim(p_name), p_store_id
  from public.store_members m
  where m.store_id = p_store_id and m.user_id = auth.uid();
end;
$$;

revoke all on function public.storevia_rename_branch(text,text,text,bigint) from public, anon;
grant execute on function public.storevia_rename_branch(text,text,text,bigint) to authenticated;
