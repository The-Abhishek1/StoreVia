# Storevia

Android inventory, sales, expiry and khata app for small retailers. Kotlin, Jetpack Compose, Room, Hilt, Supabase.

## Setup
1. Open the folder in Android Studio (Ladybug or newer, JDK 17).
2. Copy `local.properties.example` to `local.properties`; set `sdk.dir`, `supabase.url`, `supabase.anonKey` (anon key only).
3. Sync Gradle, run on a device/emulator (minSdk 24).
4. JVM tests: `./gradlew test`. Device tests (sale transaction, FEFO, rollback): `./gradlew connectedAndroidTest`.
5. Supabase: run `supabase/schema.sql` in the SQL editor.

## Architecture rules
- Money = Long paise. Quantity = Long thousandths. Never floating point.
- Stock ledger (`stock_movements`) is the source of truth; `batches.qtyOnHandMilli` is a cached balance.
- A sale is one DB transaction (`CompleteSaleUseCase`). Invoices store snapshots and are never edited.
- Room is the on-device source of truth; Supabase handles auth, backup and sync. String UUID ids and `updatedAt/deletedAt/dirty` columns are already in place for sync.
- Never use `fallbackToDestructiveMigration`. Commit `app/schemas/` and write a migration test per version bump.

## Build order from here
~~Product CRUD + barcode scan -> receive stock UI~~ (done in v0.2) -> ~~checkout screen~~ (done in v0.3) -> ~~khata screens~~ (done in v0.4) -> ~~returns and stock adjustments~~ (done in v0.5) -> ~~store setup + reports~~ (done in v0.6) -> ~~backup/restore + CSV import/export~~ (done in v0.7) -> ~~notifications + app lock~~ (done in v0.8) -> ~~checkout upgrades, cancel bill, categories, onboarding~~ (done in v0.9) -> ~~suppliers + purchases~~ (done in v0.9b) -> ~~roles + audit trail~~ (done in v0.10) -> Supabase auth, sync, cloud backup, Free/Pro plans (v1.0).

## Dev note: schema changes before the first release
The database is still version 1 and is changed in place while the app is unreleased. After pulling a new version,
uninstall the debug app (or clear its data) so Room recreates the tables. From the first public release on,
every schema change needs a Room migration and a migration test.

## Cloud sync (v1.0a: the sync core)
- `core/sync/` is the engine: it pushes rows marked `dirty` and pulls rows newer than a saved server timestamp, per table.
- Editable rows (products, customers...) are last-writer-wins. Everything that records an event (sales lines, payments, stock
  movements, ledgers, returns, purchases) is append-only, so phones can never conflict on them.
- Batch on-hand quantity and each bill's paid / returned / status are NOT synced as numbers: `CacheRebuilder` recomputes them
  from the records after every pull. `CacheRebuilderTest` proves the formulas match what the app computes during real use.
- Each phone gets its own invoice series (INV-00001, then INV-B-00001 on a second phone) so numbers never clash.
- `supabase/schema.sql` is generated: run `python3 supabase/generate_schema.py` after changing an entity or `SyncTables.kt`.
- Not wired to the UI yet (sign-in, "set up cloud", status screen, background worker come next).
- Known limits: two phones editing one product offline keep only one edit; two phones returning the same sold item offline can both
  succeed (stock then ends up one higher than it should); the server cannot enforce field-level rules such as credit limits.
