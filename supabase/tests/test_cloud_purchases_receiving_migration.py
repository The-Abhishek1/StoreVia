import re
import unittest
from pathlib import Path


MIGRATION = Path(__file__).parents[1] / "migrations" / "20261105000300_cloud_purchases_receiving.sql"
SQL = MIGRATION.read_text(encoding="utf-8").lower()


def function_body(name: str) -> str:
    match = re.search(
        rf"create or replace function public\.{name}\b.*?as \$\$(.*?)\$\$;",
        SQL,
        flags=re.DOTALL,
    )
    if not match:
        raise AssertionError(f"Missing function {name}")
    return match.group(1)


class CloudPurchaseMigrationContractTest(unittest.TestCase):
    def test_purchase_creation_is_atomic_and_idempotent(self):
        body = function_body("storevia_record_purchase")
        for marker in (
            "public.can_write(p_store_id, array['owner','manager'])",
            "public.storevia_transaction_receipts",
            "public.\"purchases\"",
            "public.\"purchase_lines\"",
            "public.\"batches\"",
            "public.\"stock_movements\"",
            "public.\"supplier_ledger\"",
            "public.\"serial_numbers\"",
        ):
            self.assertIn(marker, body)
        self.assertIn("'record_purchase'", body)

    def test_edit_and_payment_scope_every_effect_to_store(self):
        edit = function_body("storevia_edit_purchase")
        payment = function_body("storevia_pay_supplier")
        self.assertIn("id = p_purchase_id and store_id = p_store_id", edit)
        self.assertIn("where id = purchase_id and store_id = p_store_id", payment)
        self.assertIn("public.can_write(p_store_id, array['owner','manager'])", edit)
        self.assertIn("public.can_write(p_store_id, array['owner','manager'])", payment)
        self.assertIn("update public.\"purchases\"", payment)
        self.assertIn("insert into public.\"supplier_ledger\"", payment)

    def test_stock_receiving_writes_batch_and_movement_in_one_rpc(self):
        body = function_body("storevia_receive_stock")
        self.assertIn("public.can_write(p_store_id, array['owner','manager'])", body)
        self.assertIn("public.\"branches\"", body)
        self.assertIn("public.\"products\"", body)
        self.assertIn("public.\"batches\"", body)
        self.assertIn("public.\"stock_movements\"", body)
        self.assertIn("public.\"serial_numbers\"", body)
        self.assertIn("'receive_stock'", body)

    def test_rpcs_are_only_granted_to_authenticated_users(self):
        for name, signature in (
            ("storevia_record_purchase", "text,text,jsonb"),
            ("storevia_edit_purchase", "text,text,text,jsonb"),
            ("storevia_receive_stock", "text,text,jsonb"),
            ("storevia_pay_supplier", "text,text,jsonb"),
        ):
            self.assertIn(f"revoke all on function public.{name}({signature}) from public, anon, authenticated", SQL)
            self.assertIn(f"grant execute on function public.{name}({signature}) to authenticated", SQL)


if __name__ == "__main__":
    unittest.main()
