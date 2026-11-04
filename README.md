# Storevia

Android inventory, sales, expiry and khata app for small retailers. Kotlin, Jetpack Compose, Room, Hilt, Supabase.

## Setup
1. Open the folder in Android Studio (Ladybug or newer, JDK 17).
2. Copy `local.properties.example` to `local.properties`; set `sdk.dir`, `supabase.url`, `supabase.anonKey` (anon key only).
3. Sync Gradle, run on a device/emulator (minSdk 24).
4. JVM tests: `./gradlew test`. Device tests (sale transaction, FEFO, rollback): `./gradlew connectedAndroidTest`.
5. Supabase: run `supabase/schema.sql` in the SQL editor. Enable email/password sign-up and confirm email in Auth settings. Add `storevia://auth/callback` to Authentication -> URL Configuration -> Redirect URLs. Signup uses PKCE and returns confirmation callbacks to this Android deep link; the Cloud account screen can resend an expired confirmation. The script creates the private backup bucket and its row-level policies.

## Architecture rules
- UI typography uses the bundled Roboto face to remain consistent across Android OEM font settings. Roboto is licensed under Apache-2.0; see `app/src/main/assets/Roboto-LICENSE.txt`.
- Money = Long paise. Quantity = Long thousandths. Never floating point.
- Stock ledger (`stock_movements`) is the source of truth; `batches.qtyOnHandMilli` is a cached balance.
- A sale is one DB transaction (`CompleteSaleUseCase`). Invoices store snapshots and are never edited.
- Room is the on-device source of truth; Supabase handles auth, backup and sync. String UUID ids and `updatedAt/deletedAt/dirty` columns are already in place for sync.
- Never use `fallbackToDestructiveMigration`. Commit `app/schemas/` and write a migration test per version bump.

## Build order from here
~~Product CRUD + barcode scan -> receive stock UI~~ (done in v0.2) -> ~~checkout screen~~ (done in v0.3) -> ~~khata screens~~ (done in v0.4) -> ~~returns and stock adjustments~~ (done in v0.5) -> ~~store setup + reports~~ (done in v0.6) -> ~~backup/restore + CSV import/export~~ (done in v0.7) -> ~~notifications + app lock~~ (done in v0.8) -> ~~checkout upgrades, cancel bill, categories, onboarding~~ (done in v0.9) -> ~~suppliers + purchases~~ (done in v0.9b) -> ~~roles + audit trail~~ (done in v0.10) -> v1.0 cloud foundation (auth, sync, encrypted cloud backup, verified Pro subscriptions, platform admin portal).

## Dev note: schema changes
The on-device Room schema is version 7. Migrations preserve existing local stores; do not uninstall or clear app data
to apply a schema update. Every later schema change needs an additive Room migration and a migration test.

## Inventory engine additions
- Recipe/BOM products consume ingredient quantities from FEFO batches in the checkout transaction. Returns restore
  the exact ingredient batches; repeated cart lines reserve stock cumulatively.
- Serial tracking is available for whole-unit products. Receiving through stock-in or purchase entry records one
  unique serial per unit; checkout assigns serials and returns release them for resale.
- Relational sales import supports historical-only or live stock deduction, including recipe and serial-tracked
  products. Sales summary, line-item, and optional payment CSVs preserve each payment method and amount on split
  bills. Exports support CSV, paginated PDF, numeric/filterable XLSX, and tabular DOCX.
- Branches share the product catalog, but stock batches, stock movements, purchases, and sales are assigned to a
  branch. The current branch is selected per device in Store Settings; report filters can show one branch or all
  branches. Existing single-store data migrates into the original store's default branch.
- Sales exports include branch names and separate payment allocations; sales imports map exported branch names to
  existing branches and reject unknown branches. Reports can filter by supplier based on purchase history and by
  branch.

## Cloud sync (v1.0a)
- `core/sync/` is the engine: it pushes rows marked `dirty` and pulls rows newer than a saved server timestamp, per table.
- Editable rows (products, customers...) are last-writer-wins. Everything that records an event (sales lines, payments, stock
  movements, ledgers, returns, purchases) is append-only, so phones can never conflict on them.
- Batch on-hand quantity and each bill's paid / returned / status are NOT synced as numbers: `CacheRebuilder` recomputes them
  from the records after every pull. `CacheRebuilderTest` proves the formulas match what the app computes during real use.
- Each phone gets its own invoice series (INV-00001, then INV-B-00001 on a second phone) so numbers never clash.
- `supabase/schema.sql` is generated: run `python3 supabase/generate_schema.py` after changing an entity or `SyncTables.kt`.
- Welcome separates Online Cloud, Offline store account, and a restricted Platform admin entry. Online Cloud offers distinct new-account and existing-account choices. New cloud signup is stepwise: select one of eight business types, enter required store/owner/mobile/address/city/state details (GSTIN remains optional), then create credentials with password confirmation and verify the email before connecting the store. Store and owner details sync to the cloud; the phone number is saved as store contact information.
- Platform admins use a login-only route. The app verifies access with the `platform-admin` Edge Function and sends authorized users directly to the admin console; it never offers them store creation. Admin sign-out clears the cloud session and returns to Welcome.
- Offline store account opens a local workspace without cloud registration. First-time setup collects local store details; the dashboard identifies local-only data and offers Connect to Cloud and Exit. Exit returns to Welcome without deleting the local database. Connecting a populated offline store to another cloud store is rejected rather than silently replacing local records.
- After a successful connection, WorkManager runs sync every 15 minutes when the network is available. Signing out cancels scheduled sync.
- A fresh phone can join an existing cloud store when it is still at untouched first-run setup; the app makes a local safety backup before replacing placeholder data. Connecting a populated phone to a different store is rejected without changing local data.
- Backup and restore includes an encrypted cloud backup in the private `storevia-backups` bucket. Cloud upload requires a passphrase; restore still validates the archive and asks for final confirmation before replacing local data.
- New stores start on Free. The subscription row is readable by store members and writable only by trusted server functions. Play Billing purchases are verified by the Edge Function; platform admins also have an audited manual plan override for testing before Play Console is configured. Product CSV import and data exports are Pro features. The admin portal lists account email, creation date, and subscription status only, and does not expose merchant/store business data. Local file backup and restore are available on Free; cloud backup requires cloud sign-in and a connected store.
- More -> Cloud account shows the localized Play offer for a connected Free store. The platform-admin portal is available only to user IDs in `platform_admins`; its account list and Pro test overrides are handled by the `platform-admin` Edge Function and recorded in `platform_admin_audit`.
- Known limits: two phones editing one product offline keep only one edit; two phones returning the same sold item offline can both
  succeed (stock then ends up one higher than it should); the server cannot enforce field-level rules such as credit limits.

## Play Billing and platform admin setup
1. Create a subscription product in Play Console. Set `play.proProductId` in `local.properties` to its product ID; the default is `storevia_pro_monthly`.
2. Link a Google Play Developer API service account with permission to view financial data and manage orders/subscriptions. Enable the Android Publisher API for its Google Cloud project.
3. Configure Supabase Edge Function secrets: `PLAY_PACKAGE_NAME=com.xcloak.storevia`, `PLAY_PRO_PRODUCT_ID` to the exact Play product ID, and `GOOGLE_PLAY_SERVICE_ACCOUNT_JSON` to the service-account JSON. Never put this JSON in the Android app or `local.properties`.
4. Deploy `supabase/functions/verify-play-purchase` and `supabase/functions/platform-admin` with `supabase functions deploy`. Redeploy `platform-admin` after changes to its account overview so the admin app reads current cloud memberships.
5. After the first platform-admin user signs up and verifies their email, add that Auth user to the allowlist from the Supabase SQL editor: `insert into public.platform_admins (user_id) select id from auth.users where lower(email) = lower('admin@example.com');`. No mobile client can modify this allowlist.
6. Re-run `supabase/schema.sql` after pulling schema changes, then use a Play internal-test track to exercise purchases. Local APK sideloads do not provide real Play purchase eligibility.

`google-play-rtdn` verifies Pub/Sub OIDC signatures and refreshes subscription state for renewals, cancellations, holds, refunds, and expirations. Configure a Pub/Sub push subscription using an OIDC service account and audience equal to `https://<project-ref>.supabase.co/functions/v1/google-play-rtdn`, then point the Play Console RTDN topic at that Pub/Sub topic. Set `PUBSUB_PUSH_SERVICE_ACCOUNT_EMAIL` and `PUBSUB_PUSH_AUDIENCE` as Edge Function secrets. Deploy this endpoint with JWT verification disabled at the Supabase gateway because the function validates Google's OIDC JWT itself. Keep `platform-admin` and `verify-play-purchase` JWT verification enabled.
