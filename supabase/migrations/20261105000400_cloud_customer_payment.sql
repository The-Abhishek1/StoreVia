create table if not exists public.storevia_customer_payment_receipts (
  store_id text not null,
  request_id text not null,
  result_id text not null,
  created_at timestamptz not null default now(),
  primary key (store_id, request_id)
);
alter table public.storevia_customer_payment_receipts enable row level security;
revoke all on public.storevia_customer_payment_receipts from public, anon, authenticated;

create or replace function public.storevia_receive_customer_payment(
  p_store_id text,
  p_customer_id text,
  p_amount_paise bigint,
  p_method text,
  p_request_id text,
  p_created_at bigint
) returns text
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  receipt public.storevia_customer_payment_receipts%rowtype;
  customer_row public.customers%rowtype;
  sale_row public.sales%rowtype;
  payment_id text;
  first_payment_id text;
  left_to_allocate bigint;
  amount_to_allocate bigint;
  balance_paise bigint;
begin
  if auth.uid() is null or not public.can_write(p_store_id, array['OWNER', 'MANAGER', 'CASHIER']) then
    raise exception 'cloud customer payment write access required';
  end if;
  if p_request_id is null or btrim(p_request_id) = '' then
    raise exception 'request id is required';
  end if;
  if p_amount_paise is null or p_amount_paise <= 0 then
    raise exception 'payment amount must be positive';
  end if;
  if p_method not in ('CASH', 'UPI', 'CARD', 'OTHER') then
    raise exception 'payment method is invalid';
  end if;

  select * into customer_row
  from public.customers
  where store_id = p_store_id and id = p_customer_id
    and deleted_at is null and is_active <> 0
  for update;
  if not found then raise exception 'customer not found'; end if;

  select * into receipt
  from public.storevia_customer_payment_receipts
  where store_id = p_store_id and request_id = p_request_id;
  if found then return receipt.result_id; end if;

  select coalesce(sum(amount_paise), 0) into balance_paise
  from public.customer_ledger
  where store_id = p_store_id and customer_id = p_customer_id;
  if p_amount_paise > balance_paise then raise exception 'payment exceeds balance'; end if;

  left_to_allocate := p_amount_paise;
  for sale_row in
    select * from public.sales
    where store_id = p_store_id and customer_id = p_customer_id
      and status <> 'CANCELLED'
      and total_paise - returned_paise > paid_paise
    order by created_at asc, id asc
    for update
  loop
    exit when left_to_allocate = 0;
    amount_to_allocate := least(
      sale_row.total_paise - sale_row.returned_paise - sale_row.paid_paise,
      left_to_allocate
    );
    if amount_to_allocate <= 0 then continue; end if;
    payment_id := gen_random_uuid()::text;
    first_payment_id := coalesce(first_payment_id, payment_id);
    insert into public.payments (
      id, sale_id, customer_id, method, amount_paise, reference, created_at, store_id
    ) values (
      payment_id,
      sale_row.id, p_customer_id, p_method, amount_to_allocate, null,
      p_created_at, p_store_id
    );
    update public.sales
    set paid_paise = paid_paise + amount_to_allocate
    where store_id = p_store_id and id = sale_row.id;
    left_to_allocate := left_to_allocate - amount_to_allocate;
  end loop;

  if left_to_allocate > 0 then
    payment_id := gen_random_uuid()::text;
    first_payment_id := coalesce(first_payment_id, payment_id);
    insert into public.payments (
      id, sale_id, customer_id, method, amount_paise, reference, created_at, store_id
    ) values (
      payment_id, null, p_customer_id, p_method, left_to_allocate, null,
      p_created_at, p_store_id
    );
  end if;

  insert into public.customer_ledger (
    id, customer_id, type, amount_paise, ref_type, ref_id, created_at, store_id
  ) values (
    gen_random_uuid()::text, p_customer_id, 'PAYMENT_RECEIVED', -p_amount_paise,
    'PAYMENT', first_payment_id, p_created_at, p_store_id
  );

  insert into public.storevia_customer_payment_receipts (store_id, request_id, result_id)
  values (p_store_id, p_request_id, first_payment_id);
  return first_payment_id;
end;
$$;

revoke all on function public.storevia_receive_customer_payment(text, text, bigint, text, text, bigint) from public;
grant execute on function public.storevia_receive_customer_payment(text, text, bigint, text, text, bigint) to authenticated;
