-- Held carts are synchronized across a store's devices and must exist in the
-- cloud schema before any CLOUD-mode bootstrap reads the table.
create table if not exists public.held_carts (
  id text primary key,
  label text not null,
  customer_id text,
  inter_state integer not null default 0,
  bill_discount_paise bigint not null default 0,
  items_json text not null,
  item_count integer not null,
  created_at bigint not null,
  updated_at bigint not null,
  deleted_at bigint,
  store_id text not null,
  server_updated_at timestamptz not null default clock_timestamp()
);

create index if not exists held_carts_sync_idx
  on public.held_carts (store_id, server_updated_at, id);

drop trigger if exists touch_server_updated_at on public.held_carts;
create trigger touch_server_updated_at
  before insert or update on public.held_carts
  for each row execute function public.touch_server_updated_at();

alter table public.held_carts enable row level security;
drop policy if exists "members read" on public.held_carts;
create policy "members read" on public.held_carts
  for select using (public.is_member(store_id));
drop policy if exists "writers insert" on public.held_carts;
create policy "writers insert" on public.held_carts
  for insert with check (public.can_write(store_id, array['OWNER','MANAGER','CASHIER']));
drop policy if exists "writers update" on public.held_carts;
create policy "writers update" on public.held_carts
  for update using (public.can_write(store_id, array['OWNER','MANAGER','CASHIER']))
  with check (public.can_write(store_id, array['OWNER','MANAGER','CASHIER']));

notify pgrst, 'reload schema';
