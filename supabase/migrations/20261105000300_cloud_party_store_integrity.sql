-- Keep customer/supplier ledger references inside the same selected cloud store.
-- NOT VALID preserves pre-existing rows while enforcing the constraint for new writes.
create unique index if not exists customers_store_id_id_unique
    on public.customers (store_id, id);

create unique index if not exists suppliers_store_id_id_unique
    on public.suppliers (store_id, id);

do $$
begin
    if not exists (
        select 1 from pg_constraint
        where conname = 'customer_ledger_store_customer_fk'
          and conrelid = 'public.customer_ledger'::regclass
    ) then
        alter table public.customer_ledger
            add constraint customer_ledger_store_customer_fk
            foreign key (store_id, customer_id)
            references public.customers (store_id, id)
            not valid;
    end if;

    if not exists (
        select 1 from pg_constraint
        where conname = 'supplier_ledger_store_supplier_fk'
          and conrelid = 'public.supplier_ledger'::regclass
    ) then
        alter table public.supplier_ledger
            add constraint supplier_ledger_store_supplier_fk
            foreign key (store_id, supplier_id)
            references public.suppliers (store_id, id)
            not valid;
    end if;
end
$$;
