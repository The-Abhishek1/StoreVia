create or replace function public.platform_admin_deletion_scope(
  p_target_kind text,
  p_target_id text
) returns jsonb
language plpgsql
security definer
set search_path = public, auth, pg_temp
as $$
declare
  v_user_ids uuid[] := array[]::uuid[];
  v_store_ids text[] := array[]::text[];
  v_next_user_ids uuid[];
  v_next_store_ids text[];
  v_target_user_id uuid;
  v_target_name text;
  v_confirmation_text text;
  v_users jsonb;
  v_stores jsonb;
  v_row_counts jsonb := '{}'::jsonb;
  v_total_rows bigint := 0;
  v_row_count bigint;
  v_table text;
begin
  if p_target_kind = 'account' then
    begin
      v_target_user_id := p_target_id::uuid;
    exception when invalid_text_representation then
      raise exception 'Invalid account id.';
    end;
    select coalesce(u.email, v_target_user_id::text)
      into v_target_name
      from auth.users u
      where u.id = v_target_user_id;
    if not found then raise exception 'Cloud account not found.'; end if;
    v_user_ids := array[v_target_user_id];
    v_confirmation_text := v_target_name;
  elsif p_target_kind = 'store' then
    select coalesce(s.name, s.id)
      into v_target_name
      from public."store" s
      where s.id = p_target_id;
    if not found then raise exception 'Store not found.'; end if;
    v_store_ids := array[p_target_id];
    v_confirmation_text := v_target_name;
  else
    raise exception 'Deletion target must be an account or store.';
  end if;

  loop
    select coalesce(array_agg(x.store_id order by x.store_id), array[]::text[])
      into v_next_store_ids
      from (
        select unnest(v_store_ids) as store_id
        union
        select m.store_id
          from public.store_members m
          where m.user_id = any(v_user_ids)
      ) x;

    select coalesce(array_agg(x.user_id order by x.user_id), array[]::uuid[])
      into v_next_user_ids
      from (
        select unnest(v_user_ids) as user_id
        union
        select m.user_id
          from public.store_members m
          where m.store_id = any(v_next_store_ids)
      ) x;

    exit when cardinality(v_next_user_ids) = cardinality(v_user_ids)
      and cardinality(v_next_store_ids) = cardinality(v_store_ids);
    v_user_ids := v_next_user_ids;
    v_store_ids := v_next_store_ids;
  end loop;

  select coalesce(jsonb_agg(jsonb_build_object(
      'user_id', u.id,
      'email', coalesce(u.email, '')
    ) order by u.email, u.id), '[]'::jsonb)
    into v_users
    from auth.users u
    where u.id = any(v_user_ids);

  select coalesce(jsonb_agg(jsonb_build_object(
      'store_id', s.id,
      'name', coalesce(nullif(s.name, ''), s.id)
    ) order by s.name, s.id), '[]'::jsonb)
    into v_stores
    from public."store" s
    where s.id = any(v_store_ids);

  for v_table in
    select distinct c.table_name
      from information_schema.columns c
      join information_schema.tables t
        on t.table_schema = c.table_schema and t.table_name = c.table_name
      where c.table_schema = 'public'
        and c.column_name = 'store_id'
        and t.table_type = 'BASE TABLE'
  loop
    execute format('select count(*) from public.%I where store_id = any($1)', v_table)
      into v_row_count
      using v_store_ids;
    if v_row_count > 0 then
      v_row_counts := v_row_counts || jsonb_build_object(v_table, v_row_count);
      v_total_rows := v_total_rows + v_row_count;
    end if;
  end loop;

  return jsonb_build_object(
    'target_kind', p_target_kind,
    'target_id', p_target_id,
    'target_name', v_target_name,
    'confirmation_text', v_confirmation_text,
    'users', v_users,
    'user_ids', to_jsonb(v_user_ids),
    'stores', v_stores,
    'store_ids', to_jsonb(v_store_ids),
    'row_counts', v_row_counts,
    'total_rows', v_total_rows
  );
end;
$$;

create or replace function public.platform_admin_deletion_preview(
  p_target_kind text,
  p_target_id text
) returns jsonb
language sql
security definer
set search_path = public, pg_temp
as $$
  select public.platform_admin_deletion_scope(p_target_kind, p_target_id);
$$;

create or replace function public.platform_admin_execute_deletion(
  p_target_kind text,
  p_target_id text,
  p_confirmation_text text,
  p_expected_user_ids uuid[],
  p_expected_store_ids text[],
  p_expected_row_counts jsonb
) returns jsonb
language plpgsql
security definer
set search_path = public, auth, pg_temp
as $$
declare
  v_scope jsonb;
  v_user_ids uuid[];
  v_store_ids text[];
  v_remaining text[];
  v_table text;
begin
  v_scope := public.platform_admin_deletion_scope(p_target_kind, p_target_id);

  if p_confirmation_text is distinct from v_scope->>'confirmation_text' then
    raise exception 'Confirmation text does not match.';
  end if;

  select coalesce(array_agg(value::uuid order by value::uuid), array[]::uuid[])
    into v_user_ids
    from jsonb_array_elements_text(v_scope->'user_ids');
  select coalesce(array_agg(value order by value), array[]::text[])
    into v_store_ids
    from jsonb_array_elements_text(v_scope->'store_ids');

  if v_user_ids is distinct from coalesce(p_expected_user_ids, array[]::uuid[])
     or v_store_ids is distinct from coalesce(p_expected_store_ids, array[]::text[])
     or v_scope->'row_counts' is distinct from coalesce(p_expected_row_counts, '{}'::jsonb) then
    raise exception 'Deletion scope changed. Refresh the impact preview and try again.';
  end if;

  update public.platform_payment_records
    set recorded_by = null
    where recorded_by = any(v_user_ids);
  update public.platform_admin_audit
    set actor_id = null
    where actor_id = any(v_user_ids);

  select array_agg(c.relname order by c.relname)
    into v_remaining
    from pg_class c
    join pg_namespace n on n.oid = c.relnamespace
    where n.nspname = 'public'
      and c.relkind in ('r', 'p')
      and exists (
        select 1
          from pg_attribute a
          where a.attrelid = c.oid and a.attname = 'store_id' and not a.attisdropped
      );
  v_remaining := coalesce(v_remaining, array[]::text[]);

  while cardinality(v_remaining) > 0 loop
    select parent.relname
      into v_table
      from unnest(v_remaining) candidate(relname)
      join pg_class parent on parent.relname = candidate.relname
      join pg_namespace parent_ns on parent_ns.oid = parent.relnamespace and parent_ns.nspname = 'public'
      where not exists (
        select 1
          from pg_constraint fk
          join pg_class child on child.oid = fk.conrelid
          join pg_namespace child_ns on child_ns.oid = child.relnamespace
          where fk.contype = 'f'
            and fk.confrelid = parent.oid
            and child_ns.nspname = 'public'
            and child.relname = any(v_remaining)
            and child.oid <> parent.oid
      )
      order by parent.relname
      limit 1;

    if v_table is null then
      raise exception 'Could not safely order store data deletion because of a circular relationship.';
    end if;

    execute format('delete from public.%I where store_id = any($1)', v_table)
      using v_store_ids;
    v_remaining := array_remove(v_remaining, v_table);
    v_table := null;
  end loop;

  return jsonb_build_object(
    'user_ids', to_jsonb(v_user_ids),
    'store_ids', to_jsonb(v_store_ids)
  );
end;
$$;

revoke all on function public.platform_admin_deletion_scope(text, text) from public, anon, authenticated;
revoke all on function public.platform_admin_deletion_preview(text, text) from public, anon, authenticated;
revoke all on function public.platform_admin_execute_deletion(text, text, text, uuid[], text[], jsonb) from public, anon, authenticated;
grant execute on function public.platform_admin_deletion_preview(text, text) to service_role;
grant execute on function public.platform_admin_execute_deletion(text, text, text, uuid[], text[], jsonb) to service_role;
