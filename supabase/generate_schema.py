#!/usr/bin/env python3
"""
Generates supabase/schema.sql.

The synced tables are generated FROM the Kotlin entities and SyncTables.kt, so the phone and the server can't drift
apart: same columns (snake_case), same kinds (mutable / append-only), same local-only exclusions.
SQL files in supabase/migrations are appended to the generated schema.
Run from the project root:  python3 supabase/generate_schema.py
"""
import re, pathlib

root = pathlib.Path(__file__).resolve().parent.parent
kt = root / "app/src/main/java/com/xcloak/storevia"
entities_src = (kt / "core/database/entity/Entities.kt").read_text() + (kt / "core/database/entity/AccessEntities.kt").read_text()
sync_src = (kt / "core/sync/SyncTables.kt").read_text()

def snake(n): return "".join("_" + c.lower() if c.isupper() else c for c in n)

# ---- which tables sync, how, and what stays local (read from SyncTables.kt)
tables = []
dependencies_by_table = {}
def balanced(src, start):
    """Text of the call whose opening '(' is at src[start], matching nested parentheses."""
    depth = 0
    for i in range(start, len(src)):
        if src[i] == "(": depth += 1
        elif src[i] == ")":
            depth -= 1
            if depth == 0: return src[start + 1:i]
    raise SystemExit("unbalanced SyncTable( in SyncTables.kt")

for m in re.finditer(r'SyncTable\(', sync_src):
    if sync_src[max(0, m.start() - 11):m.start()].endswith("class "): continue      # skip the data class declaration
    body = balanced(sync_src, m.end() - 1)
    head = re.match(r'\s*"(\w+)",\s*SyncKind\.(\w+)', body)
    if not head: continue
    lo = re.search(r'localOnly\s*=\s*setOf\(([^)]*)\)', body)
    local = set(re.findall(r'"(\w+)"', lo.group(1))) if lo else set()
    tables.append((head.group(1), head.group(2), local))
    deps = re.search(r'dependsOn\s*=\s*setOf\(([^)]*)\)', body)
    dependencies_by_table[head.group(1)] = set(re.findall(r'"(\w+)"', deps.group(1))) if deps else set()

# ---- columns of every entity
cols_by_table = {}
entity_by_table = {}
table_by_entity = {}
foreign_keys_by_table = {}
for m in re.finditer(r'@Entity\((.*?)\)\s*data class (\w+)\(\n(.*?)\n\)', entities_src, re.S):
    tname = re.search(r'tableName\s*=\s*"(\w+)"', m.group(1))
    if not tname: continue
    table_name = tname.group(1)
    cols = []
    for line in m.group(3).splitlines():
        fm = re.match(r'\s*(?:@\w+\s+)*val (\w+):\s*([\w.]+\??)(?:\s*=\s*(.*?))?,?\s*$', line)
        if fm: cols.append((fm.group(1), fm.group(2), fm.group(3)))
    cols_by_table[table_name] = cols
    entity_by_table[table_name] = (m.group(1), m.group(2))
    table_by_entity[m.group(2)] = table_name

for table_name, (annotation, _) in entity_by_table.items():
    foreign_keys = []
    for parent_class, parent_cols, child_cols, options in re.findall(
        r'ForeignKey\((\w+)::class,\s*\[([^\]]*)\],\s*\[([^\]]*)\](.*?)\)',
        annotation,
        re.S,
    ):
        parent_table = table_by_entity.get(parent_class)
        if parent_table is None:
            raise SystemExit(f"unknown foreign-key parent {parent_class} for {table_name}")
        parent_columns = re.findall(r'"(\w+)"', parent_cols)
        child_columns = re.findall(r'"(\w+)"', child_cols)
        if len(parent_columns) != len(child_columns):
            raise SystemExit(f"mismatched foreign-key columns for {table_name}")
        on_delete = re.search(r'onDelete\s*=\s*ForeignKey\.(CASCADE|RESTRICT|SET_NULL|NO_ACTION)', options)
        foreign_keys.append((parent_table, child_columns, parent_columns, on_delete.group(1) if on_delete else "NO_ACTION"))
    foreign_keys_by_table[table_name] = foreign_keys

table_order = {name: index for index, (name, _, _) in enumerate(tables)}
for table_name, _, _ in tables:
    for dependency in dependencies_by_table.get(table_name, set()):
        if dependency in table_order and table_order[dependency] >= table_order[table_name]:
            raise SystemExit(f"{dependency} must be listed before dependent sync table {table_name}")
for table_name, foreign_keys in foreign_keys_by_table.items():
    for parent_table, _, _, _ in foreign_keys:
        if table_name in table_order and parent_table in table_order and table_order[parent_table] >= table_order[table_name]:
            raise SystemExit(f"{parent_table} must be listed before foreign-key child {table_name}")

def sql_type(t):
    t = t.rstrip("?")
    return {"Long": "bigint", "Int": "integer", "Boolean": "integer"}.get(t, "text")   # booleans travel as 0/1; enums, dates, strings as text

def sql_default(t, expression):
    if expression is None or expression == "null":
        return None
    expression = expression.strip()
    if t == "Boolean":
        return "1" if expression == "true" else "0" if expression == "false" else None
    if t in {"Long", "Int"}:
        if re.fullmatch(r"-?\d+[lL]?", expression):
            return expression.rstrip("lL")
        if "System.currentTimeMillis()" in expression or re.fullmatch(r"\w+", expression):
            return "(extract(epoch from clock_timestamp()) * 1000)::bigint"
    if expression.startswith('"') and expression.endswith('"'):
        return "'" + expression[1:-1].replace("'", "''") + "'"
    enum_value = re.fullmatch(r"[\w.]+\.([A-Z][A-Z0-9_]*)", expression)
    if enum_value:
        return "'" + enum_value.group(1) + "'"
    return None

OWNER, MANAGER, CASHIER = "array['OWNER']", "array['OWNER','MANAGER']", "array['OWNER','MANAGER','CASHIER']"
WRITE_ROLES = {}
for t in ["store", "staff"]: WRITE_ROLES[t] = OWNER
for t in ["branches", "units", "categories", "products", "product_barcodes", "batches", "suppliers", "purchases", "purchase_lines",
          "supplier_ledger", "sale_returns", "sale_return_lines", "recipe_components", "serial_numbers"]: WRITE_ROLES[t] = MANAGER
for t in ["customers", "held_carts", "sales", "sale_lines", "payments", "stock_movements", "customer_ledger", "audit_log"]: WRITE_ROLES[t] = CASHIER

out = []
w = out.append
w("""-- Storevia server schema (Supabase / PostgreSQL). GENERATED by supabase/generate_schema.py: do not edit the
-- synced-table sections by hand. Run the whole file in the Supabase SQL editor.
--
-- Model: one shop = one store_id (the same id the phone uses). People belong to a shop through store_members with a role.
-- Row Level Security: members can READ their shop's rows; WRITING needs the right role AND an active plan (Free or Pro).
-- Append-only tables (ledgers, sales lines...) can only be inserted, never updated or deleted by any client.
-- IMPORTANT: in Authentication settings, turn ON "Confirm email", otherwise anyone could claim an invite by
-- signing up with someone else's address.

create extension if not exists pgcrypto;

-- ---------------------------------------------------------------- shop membership, devices, plan
create table if not exists public.store_members (
  store_id text not null,
  user_id uuid not null references auth.users(id) on delete cascade,
  role text not null check (role in ('OWNER','MANAGER','CASHIER')),
  display_name text,
  created_at timestamptz not null default now(),
  primary key (store_id, user_id)
);

create table if not exists public.store_invites (
  store_id text not null,
  email text not null,
  role text not null check (role in ('MANAGER','CASHIER')),
  created_at timestamptz not null default now(),
  primary key (store_id, email)
);

alter table public.store_invites drop constraint if exists store_invites_role_check;
alter table public.store_invites add constraint store_invites_role_check check (role in ('OWNER','MANAGER','CASHIER'));

-- One row per phone. 'series' keeps invoice numbers apart: first phone '', then B, C, D...
create table if not exists public.devices (
  store_id text not null,
  device_id text not null,
  series text not null,
  created_at timestamptz not null default now(),
  primary key (store_id, device_id),
  unique (store_id, series)
);

-- Written only by the server (Free access on creation, Google Play verification later). Clients can read, never write.
create table if not exists public.subscriptions (
  store_id text primary key,
  plan text not null default 'free',
  status text not null default 'active' check (status in ('active','expired','cancelled')),
  expires_at timestamptz not null,
  product_id text,
  purchase_token text,
  purchase_token_hash text,
  updated_at timestamptz not null default now()
);

alter table public.subscriptions add column if not exists purchase_token_hash text;
update public.subscriptions
set purchase_token_hash = encode(digest(purchase_token, 'sha256'), 'hex'), purchase_token = null
where purchase_token is not null and purchase_token_hash is null;
update public.subscriptions set purchase_token = null where purchase_token is not null;
alter table public.subscriptions drop constraint if exists subscriptions_plan_check;
update public.subscriptions set plan = 'free', expires_at = now() + interval '100 years' where plan = 'trial';
alter table public.subscriptions add constraint subscriptions_plan_check check (plan in ('free','pro'));
alter table public.subscriptions drop constraint if exists subscriptions_status_check;
alter table public.subscriptions add constraint subscriptions_status_check check (status in ('active','expired','cancelled','held','deleted'));

-- Platform administrators are assigned out-of-band by a trusted operator after auth.users accounts exist.
-- Never expose insert/update/delete access for this allowlist to mobile clients.
create table if not exists public.platform_admins (
  user_id uuid primary key references auth.users(id) on delete cascade,
  created_at timestamptz not null default now(),
  created_by text
);

create table if not exists public.platform_store_metadata (
  store_id text primary key,
  store_type text not null default 'GENERAL',
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint platform_store_metadata_store_type_check
    check (store_type in ('GENERAL','MEDICAL','WHOLESALER','RESTAURANT','CLOTHING','ELECTRONICS','SERVICE','OTHER'))
);

create table if not exists public.platform_payment_records (
  id uuid primary key default gen_random_uuid(),
  store_id text not null,
  amount_minor bigint,
  currency text not null default 'INR',
  provider text not null check (provider in ('google_play','manual')),
  product_id text,
  external_reference text,
  status text not null check (status in ('verified','recorded','refunded','failed')),
  note text,
  recorded_by uuid references auth.users(id),
  created_at timestamptz not null default now(),
  unique (provider, external_reference)
);

create table if not exists public.platform_admin_audit (
  id uuid primary key default gen_random_uuid(),
  actor_id uuid references auth.users(id),
  action text not null,
  store_id text,
  summary text not null,
  created_at timestamptz not null default now()
);

alter table public.platform_admins enable row level security;
alter table public.platform_store_metadata enable row level security;
alter table public.platform_payment_records enable row level security;
alter table public.platform_admin_audit enable row level security;
-- These tables have no client policies. Only trusted Edge Functions using service_role may access them.

create or replace function public.platform_create_store(
  p_store_id text,
  p_name text,
  p_owner_email text,
  p_store_type text,
  p_actor_id uuid
) returns void
language plpgsql security definer set search_path = public as $$
begin
  if not exists (select 1 from public.platform_admins where user_id = p_actor_id) then
    raise exception 'platform administrator access required';
  end if;
  if btrim(p_name) = '' or position('@' in p_owner_email) < 2 or
     p_store_type not in ('GENERAL','MEDICAL','WHOLESALER','RESTAURANT','CLOTHING','ELECTRONICS','SERVICE','OTHER') then
    raise exception 'invalid store details';
  end if;

  insert into public."store" (
    id, store_id, name, address, phone, gstin, state_code, invoice_prefix,
    allow_negative_stock, created_at, updated_at
  ) values (
    p_store_id, p_store_id, btrim(p_name), '', '', null, '29', 'INV', 1,
    (extract(epoch from clock_timestamp()) * 1000)::bigint,
    (extract(epoch from clock_timestamp()) * 1000)::bigint
  );
  insert into public.subscriptions (store_id, plan, status, expires_at)
    values (p_store_id, 'free', 'active', '9999-12-31 23:59:59+00');
  insert into public.platform_store_metadata (store_id, store_type)
    values (p_store_id, p_store_type);
  insert into public.store_invites (store_id, email, role)
    values (p_store_id, lower(btrim(p_owner_email)), 'OWNER');
  insert into public.platform_admin_audit (actor_id, action, store_id, summary)
    values (p_actor_id, 'STORE_CREATED', p_store_id, 'Created ' || p_store_type || ' store ' || btrim(p_name) || ' and invited ' || lower(btrim(p_owner_email)) || ' as owner.');
end;
$$;

revoke all on function public.platform_create_store(text,text,text,text,uuid) from public, anon, authenticated;
grant execute on function public.platform_create_store(text,text,text,text,uuid) to service_role;

-- ---------------------------------------------------------------- helper functions
create or replace function public.is_member(sid text) returns boolean
language sql stable security definer set search_path = public as $$
  select exists (select 1 from public.store_members m where m.store_id = sid and m.user_id = auth.uid());
$$;

create or replace function public.store_is_active(sid text) returns boolean
language sql stable security definer set search_path = public as $$
  select exists (
    select 1 from public.subscriptions s
    where s.store_id = sid and s.status = 'active' and (s.plan = 'free' or s.expires_at > now())
  );
$$;

create or replace function public.can_write(sid text, roles text[]) returns boolean
language sql stable security definer set search_path = public as $$
  select public.store_is_active(sid)
     and exists (select 1 from public.store_members m where m.store_id = sid and m.user_id = auth.uid() and m.role = any(roles));
$$;

create or replace function public.is_owner(sid text) returns boolean
language sql stable security definer set search_path = public as $$
  select exists (select 1 from public.store_members m where m.store_id = sid and m.user_id = auth.uid() and m.role = 'OWNER');
$$;

create or replace function public.touch_server_updated_at() returns trigger
language plpgsql as $$
begin
  new.server_updated_at := clock_timestamp();
  return new;
end;
$$;

-- ---------------------------------------------------------------- calls the app makes
-- Start cloud sync for a shop: caller becomes its owner on the Free plan.
create or replace function public.create_store(p_store_id text, p_name text) returns void
language plpgsql security definer set search_path = public as $$
begin
  if auth.uid() is null then raise exception 'not signed in'; end if;
  if exists (select 1 from public.store_members where store_id = p_store_id) then raise exception 'store already exists'; end if;
  insert into public.store_members (store_id, user_id, role, display_name) values (p_store_id, auth.uid(), 'OWNER', p_name);
  insert into public.subscriptions (store_id, plan, status, expires_at)   values (p_store_id, 'free', 'active', now() + interval '100 years');
end;
$$;

create or replace function public.create_store_with_details(
  p_store_id text,
  p_name text,
  p_owner_name text,
  p_store_type text
) returns void
language plpgsql security definer set search_path = public as $$
begin
  if auth.uid() is null then raise exception 'not signed in'; end if;
  if btrim(p_name) = '' or btrim(p_owner_name) = '' or
     p_store_type not in ('GENERAL','MEDICAL','WHOLESALER','RESTAURANT','CLOTHING','ELECTRONICS','SERVICE','OTHER') then
    raise exception 'invalid store details';
  end if;
  if exists (select 1 from public.store_members where store_id = p_store_id) then raise exception 'store already exists'; end if;
  insert into public.store_members (store_id, user_id, role, display_name) values (p_store_id, auth.uid(), 'OWNER', btrim(p_owner_name));
  insert into public.subscriptions (store_id, plan, status, expires_at) values (p_store_id, 'free', 'active', now() + interval '100 years');
  insert into public.platform_store_metadata (store_id, store_type)
    values (p_store_id, p_store_type)
    on conflict (store_id) do update set store_type = excluded.store_type, updated_at = now();
end;
$$;

-- All stores the signed-in person belongs to, in stable order, with their roles and configured types.
-- Return every membership in a stable order so clients can reconnect to their persisted selection.
drop function if exists public.my_store();
create function public.my_store() returns table (store_id text, role text, store_type text)
language sql stable security definer set search_path = public as $$
  select m.store_id, m.role, coalesce(meta.store_type, 'GENERAL')
  from public.store_members m
  left join public.platform_store_metadata meta on meta.store_id = m.store_id
  where m.user_id = auth.uid()
  order by m.created_at, m.store_id;
$$;

-- Gives this phone its invoice series. Calling again with the same device returns the same series.
create or replace function public.register_device(p_store_id text, p_device_id text) returns text
language plpgsql security definer set search_path = public as $$
declare n integer; s text;
begin
  if not public.is_member(p_store_id) then raise exception 'not a member'; end if;
  select series into s from public.devices where store_id = p_store_id and device_id = p_device_id;
  if found then return s; end if;
  select count(*) into n from public.devices where store_id = p_store_id;
  if n >= 25 then raise exception 'too many devices'; end if;
  s := case when n = 0 then '' else chr(65 + n) end;
  insert into public.devices (store_id, device_id, series) values (p_store_id, p_device_id, s);
  return s;
end;
$$;

create or replace function public.invite_member(p_store_id text, p_email text, p_role text) returns void
language plpgsql security definer set search_path = public as $$
begin
  if not public.is_owner(p_store_id) then raise exception 'only the owner can invite'; end if;
  if p_role not in ('MANAGER','CASHIER') then raise exception 'bad role'; end if;
  insert into public.store_invites (store_id, email, role) values (p_store_id, lower(p_email), p_role)
  on conflict (store_id, email) do update set role = excluded.role;
end;
$$;

-- Turns any invitation addressed to the signed-in person's (confirmed) email into membership.
create or replace function public.accept_invites() returns integer
language plpgsql security definer set search_path = public as $$
declare mail text; r record; c integer := 0;
begin
  mail := lower(auth.jwt() ->> 'email');
  if auth.uid() is null or mail is null then return 0; end if;
  for r in select * from public.store_invites where email = mail loop
    insert into public.store_members (store_id, user_id, role) values (r.store_id, auth.uid(), r.role) on conflict do nothing;
    delete from public.store_invites where store_id = r.store_id and email = r.email;
    c := c + 1;
  end loop;
  return c;
end;
$$;

alter table public.store_members enable row level security;
alter table public.store_invites enable row level security;
alter table public.devices enable row level security;
alter table public.subscriptions enable row level security;

drop policy if exists "members read members" on public.store_members;
create policy "members read members" on public.store_members for select using (public.is_member(store_id));
drop policy if exists "owner reads invites" on public.store_invites;
create policy "owner reads invites" on public.store_invites for select using (public.is_owner(store_id));
drop policy if exists "members read devices" on public.devices;
create policy "members read devices" on public.devices for select using (public.is_member(store_id));
drop policy if exists "members read plan" on public.subscriptions;
create policy "members read plan" on public.subscriptions for select using (public.is_member(store_id));
-- No insert/update/delete policies on these four tables: only the functions above (security definer) and the server write them.

-- ---------------------------------------------------------------- private encrypted store backups
insert into storage.buckets (id, name, public)
values ('storevia-backups', 'storevia-backups', false)
on conflict (id) do nothing;

drop policy if exists "store members read backups" on storage.objects;
create policy "store members read backups" on storage.objects
for select using (
  bucket_id = 'storevia-backups'
  and public.is_member((storage.foldername(name))[1])
);

drop policy if exists "store managers upload backups" on storage.objects;
create policy "store managers upload backups" on storage.objects
for insert with check (
  bucket_id = 'storevia-backups'
  and public.can_write((storage.foldername(name))[1], array['OWNER','MANAGER'])
);

drop policy if exists "store managers update backups" on storage.objects;
create policy "store managers update backups" on storage.objects
for update using (
  bucket_id = 'storevia-backups'
  and public.can_write((storage.foldername(name))[1], array['OWNER','MANAGER'])
) with check (
  bucket_id = 'storevia-backups'
  and public.can_write((storage.foldername(name))[1], array['OWNER','MANAGER'])
);

-- ---------------------------------------------------------------- synced tables (generated)
""")

for name, kind, local in tables:
    cols = [(c, t, default) for c, t, default in cols_by_table[name] if c != "dirty" and c not in local]
    w(f"-- {name}: {kind.lower().replace('_', '-')}")
    w(f'create table if not exists public."{name}" (')
    for c, t, _ in cols:
        w(f'  "{snake(c)}" {sql_type(t)}{"" if t.endswith("?") else " not null"},')
    w("  store_id text not null,")
    w("  server_updated_at timestamptz not null default clock_timestamp(),")
    foreign_keys = foreign_keys_by_table.get(name, [])
    for index, (parent, child_cols, parent_cols, on_delete) in enumerate(foreign_keys):
        child_sql = ", ".join(f'"{snake(c)}"' for c in child_cols)
        parent_sql = ", ".join(f'"{snake(c)}"' for c in parent_cols)
        constraint = f"{name}_{'_'.join(snake(c) for c in child_cols)}_fkey"
        delete_clause = "" if on_delete == "NO_ACTION" else f" on delete {on_delete.lower().replace('_', ' ')}"
        w(f'  constraint "{constraint}" foreign key ({child_sql}) references public."{parent}" ({parent_sql}){delete_clause},')
    w('  primary key ("id")')
    w(");")
    for child_parent, child_cols, parent_cols, on_delete in foreign_keys:
        child_sql = ", ".join(f'"{snake(c)}"' for c in child_cols)
        parent_sql = ", ".join(f'"{snake(c)}"' for c in parent_cols)
        constraint = f"{name}_{'_'.join(snake(c) for c in child_cols)}_fkey"
        delete_clause = "" if on_delete == "NO_ACTION" else f" on delete {on_delete.lower().replace('_', ' ')}"
        w(f"""do $$
begin
  if not exists (
    select 1 from pg_constraint
    where conname = '{constraint}' and conrelid = 'public."{name}"'::regclass
  ) then
    alter table public."{name}" add constraint "{constraint}"
      foreign key ({child_sql}) references public."{child_parent}" ({parent_sql}){delete_clause};
  end if;
end;
$$;""")
    for c, t, default in cols:
        if c == "id":
            continue
        nullable = t.endswith("?")
        column_default = sql_default(t, default)
        definition = sql_type(t)
        if not nullable and column_default is not None:
            definition += f" not null default {column_default}"
        w(f'alter table public."{name}" add column if not exists "{snake(c)}" {definition};')
    if name in {"batches", "stock_movements", "sales", "purchases"}:
        w(f'alter table public."{name}" add column if not exists "branch_id" text not null default \'default\';')
        w(f'update public."{name}" set "branch_id" = store_id where "branch_id" = \'default\';')
        w(f'create index if not exists "{name}_branch_idx" on public."{name}" (store_id, "branch_id");')
    if name == "branches":
        w('create unique index if not exists "branches_store_name_unique" on public."branches" (store_id, "name");')
    if name == "serial_numbers":
        w('create unique index if not exists "serial_numbers_store_serial_unique" on public."serial_numbers" (store_id, "serial_number");')
    w(f'create index if not exists "{name}_sync_idx" on public."{name}" (store_id, server_updated_at, "id");')
    w(f'drop trigger if exists touch_server_updated_at on public."{name}";')
    w(f'create trigger touch_server_updated_at before insert or update on public."{name}" for each row execute function public.touch_server_updated_at();')
    w(f'alter table public."{name}" enable row level security;')
    roles = WRITE_ROLES[name]
    w(f'drop policy if exists "members read" on public."{name}";')
    w(f'create policy "members read" on public."{name}" for select using (public.is_member(store_id));')
    w(f'drop policy if exists "writers insert" on public."{name}";')
    w(f'create policy "writers insert" on public."{name}" for insert with check (public.can_write(store_id, {roles}));')
    if kind == "MUTABLE":
        w(f'drop policy if exists "writers update" on public."{name}";')
        w(f'create policy "writers update" on public."{name}" for update using (public.can_write(store_id, {roles})) with check (public.can_write(store_id, {roles}));')
    w("")

w("""-- Existing single-store data becomes that store's first branch.
insert into public."branches" ("id", "name", "address", "city", "phone", "is_active", "created_at", "updated_at", "deleted_at", store_id)
select s."id", s."name", s."address", s."city", s."phone", 1, s."created_at", s."updated_at", null, s."id"
from public."store" s
on conflict ("id") do nothing;
""")

for migration in sorted((root / "supabase/migrations").glob("*.sql")):
    w(f"-- Included migration: {migration.name}")
    w(migration.read_text().rstrip())
    w("")

w("notify pgrst, 'reload schema';")

(root / "supabase/schema.sql").write_text("\n".join(out))
print("tables:", len(tables), "| lines:", len(out))
for name, kind, local in tables:
    n = len([c for c, _, _ in cols_by_table[name] if c != "dirty" and c not in local])
    print(f"  {name:20s} {kind:12s} {n:2d} cols" + (f"  (kept on phone: {', '.join(sorted(local))})" if local else ""))
