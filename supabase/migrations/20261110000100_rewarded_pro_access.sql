create table if not exists public.ad_reward_nonces (
  nonce text primary key,
  store_id text not null,
  user_id uuid not null references auth.users(id) on delete cascade,
  expires_at timestamptz not null,
  used_at timestamptz,
  created_at timestamptz not null default now()
);

create unique index if not exists ad_reward_nonces_pending_user_store_idx
  on public.ad_reward_nonces (store_id, user_id)
  where used_at is null;

create table if not exists public.ad_reward_entitlements (
  store_id text not null,
  user_id uuid not null references auth.users(id) on delete cascade,
  expires_at timestamptz not null,
  updated_at timestamptz not null default now(),
  primary key (store_id, user_id)
);

create table if not exists public.ad_reward_events (
  transaction_id text primary key,
  nonce text not null unique references public.ad_reward_nonces(nonce),
  store_id text not null,
  user_id uuid not null references auth.users(id) on delete cascade,
  rewarded_at timestamptz not null default now(),
  expires_at timestamptz not null
);

alter table public.ad_reward_nonces enable row level security;
alter table public.ad_reward_entitlements enable row level security;
alter table public.ad_reward_events enable row level security;

revoke all on public.ad_reward_nonces, public.ad_reward_entitlements, public.ad_reward_events
  from public, anon, authenticated;
grant select on public.ad_reward_entitlements to authenticated;

drop policy if exists "users read own rewarded access" on public.ad_reward_entitlements;
create policy "users read own rewarded access" on public.ad_reward_entitlements
  for select to authenticated
  using (user_id = auth.uid() and public.is_member(store_id));

create or replace function public.has_active_pro(sid text) returns boolean
language sql stable security definer set search_path = public as $$
  select public.is_member(sid) and (
    exists (
      select 1
      from public.subscriptions s
      where s.store_id = sid
        and s.plan = 'pro'
        and s.status = 'active'
        and s.expires_at > now()
    )
    or exists (
      select 1
      from public.ad_reward_entitlements r
      where r.store_id = sid
        and r.user_id = auth.uid()
        and r.expires_at > now()
    )
  );
$$;

revoke all on function public.has_active_pro(text) from public, anon;
grant execute on function public.has_active_pro(text) to authenticated;

create or replace function public.storevia_grant_ad_reward(
  p_transaction_id text,
  p_nonce text
) returns timestamptz
language plpgsql security definer set search_path = public as $$
declare
  reward_nonce public.ad_reward_nonces%rowtype;
  next_expiry timestamptz;
  prior_expiry timestamptz;
begin
  if btrim(p_transaction_id) = '' or btrim(p_nonce) = '' then
    raise exception 'invalid rewarded-ad callback';
  end if;

  select expires_at into prior_expiry
  from public.ad_reward_events
  where transaction_id = p_transaction_id;
  if found then
    return prior_expiry;
  end if;

  select * into reward_nonce
  from public.ad_reward_nonces
  where nonce = p_nonce
  for update;
  if not found or reward_nonce.used_at is not null or reward_nonce.expires_at <= now() then
    raise exception 'reward request is missing, expired, or already used';
  end if;
  if not exists (
    select 1 from public.store_members
    where store_id = reward_nonce.store_id and user_id = reward_nonce.user_id
  ) then
    raise exception 'rewarded user is no longer a store member';
  end if;

  select greatest(expires_at, now()) into prior_expiry
  from public.ad_reward_entitlements
  where store_id = reward_nonce.store_id and user_id = reward_nonce.user_id
  for update;
  next_expiry := coalesce(prior_expiry, now()) + interval '15 minutes';

  insert into public.ad_reward_entitlements (store_id, user_id, expires_at, updated_at)
  values (reward_nonce.store_id, reward_nonce.user_id, next_expiry, now())
  on conflict (store_id, user_id) do update
    set expires_at = excluded.expires_at, updated_at = excluded.updated_at;

  insert into public.ad_reward_events (transaction_id, nonce, store_id, user_id, expires_at)
  values (p_transaction_id, p_nonce, reward_nonce.store_id, reward_nonce.user_id, next_expiry);
  update public.ad_reward_nonces set used_at = now() where nonce = p_nonce;

  return next_expiry;
end;
$$;

revoke all on function public.storevia_grant_ad_reward(text, text) from public, anon, authenticated;
grant execute on function public.storevia_grant_ad_reward(text, text) to service_role;
