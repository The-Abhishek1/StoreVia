# Storevia

Android inventory, sales, expiry and khata app for small retailers. Kotlin, Jetpack Compose, Room, Hilt, Supabase.

## Setup
1. Open the folder in Android Studio (Ladybug or newer, JDK 17).
2. Copy `local.properties.example` to `local.properties`; set `sdk.dir`, `supabase.url`, `supabase.anonKey` (anon key only).
3. Sync Gradle, run on a device/emulator (minSdk 24).
4. JVM tests: `./gradlew test`. Device tests (sale transaction, FEFO, rollback): `./gradlew connectedAndroidTest`.
5. Supabase: run `supabase/schema.sql` in the SQL editor. Enable email/password sign-up and confirm email in Auth settings. Add `storevia://auth/callback` to Authentication -> URL Configuration -> Redirect URLs. Signup uses PKCE and returns confirmation callbacks to this Android deep link; the Cloud account screen can resend an expired confirmation. The script creates the private backup bucket and its row-level policies.

## Architecture rules
- Money = Long paise. Quantity = Long thousandths. Never floating point.
- Stock ledger (`stock_movements`) is the source of truth; `batches.qtyOnHandMilli` is a cached balance.
- A sale is one DB transaction (`CompleteSaleUseCase`). Invoices store snapshots and are never edited.
- Room is the on-device source of truth; Supabase handles auth, backup and sync. String UUID ids and `updatedAt/deletedAt/dirty` columns are already in place for sync.
- Never use `fallbackToDestructiveMigration`. Commit `app/schemas/` and write a migration test per version bump.

## Build order from here
~~Product CRUD + barcode scan -> receive stock UI~~ (done in v0.2) -> ~~checkout screen~~ (done in v0.3) -> ~~khata screens~~ (done in v0.4) -> ~~returns and stock adjustments~~ (done in v0.5) -> ~~store setup + reports~~ (done in v0.6) -> ~~backup/restore + CSV import/export~~ (done in v0.7) -> ~~notifications + app lock~~ (done in v0.8) -> ~~checkout upgrades, cancel bill, categories, onboarding~~ (done in v0.9) -> ~~suppliers + purchases~~ (done in v0.9b) -> ~~roles + audit trail~~ (done in v0.10) -> v1.0 cloud foundation (auth, sync, encrypted cloud backup, verified Pro subscriptions, platform admin portal).

## Dev note: schema changes before the first release
The database is still version 1 and is changed in place while the app is unreleased. After pulling a new version,
uninstall the debug app (or clear its data) so Room recreates the tables. From the first public release on,
every schema change needs a Room migration and a migration test.

## Cloud sync (v1.0a)
- `core/sync/` is the engine: it pushes rows marked `dirty` and pulls rows newer than a saved server timestamp, per table.
- Editable rows (products, customers...) are last-writer-wins. Everything that records an event (sales lines, payments, stock
  movements, ledgers, returns, purchases) is append-only, so phones can never conflict on them.
- Batch on-hand quantity and each bill's paid / returned / status are NOT synced as numbers: `CacheRebuilder` recomputes them
  from the records after every pull. `CacheRebuilderTest` proves the formulas match what the app computes during real use.
- Each phone gets its own invoice series (INV-00001, then INV-B-00001 on a second phone) so numbers never clash.
- `supabase/schema.sql` is generated: run `python3 supabase/generate_schema.py` after changing an entity or `SyncTables.kt`.
- More -> Cloud account provides email sign-up/sign-in, first-store cloud setup, account/role/plan status, manual sync, and sign-out.
- Startup offers separate online sign-in and account creation choices, platform-admin sign-in, and local-only mode. The platform-admin sign-in screen also offers the local-only business app; this does not grant offline access to platform-admin tools, which remain restricted to authorized online accounts. Returning authenticated stores go directly to the dashboard. Work offline is an explicit local-only fallback with a persistent Connect account action; signing in there routes normal users to store setup/join and allowlisted admins to the admin portal.
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
4. Deploy `supabase/functions/verify-play-purchase` and `supabase/functions/platform-admin` with `supabase functions deploy`.
5. After the first platform-admin user signs up and verifies their email, add that Auth user to the allowlist from the Supabase SQL editor: `insert into public.platform_admins (user_id) select id from auth.users where lower(email) = lower('admin@example.com');`. No mobile client can modify this allowlist.
6. Re-run `supabase/schema.sql` after pulling schema changes, then use a Play internal-test track to exercise purchases. Local APK sideloads do not provide real Play purchase eligibility.

`google-play-rtdn` verifies Pub/Sub OIDC signatures and refreshes subscription state for renewals, cancellations, holds, refunds, and expirations. Configure a Pub/Sub push subscription using an OIDC service account and audience equal to `https://<project-ref>.supabase.co/functions/v1/google-play-rtdn`, then point the Play Console RTDN topic at that Pub/Sub topic. Set `PUBSUB_PUSH_SERVICE_ACCOUNT_EMAIL` and `PUBSUB_PUSH_AUDIENCE` as Edge Function secrets. Deploy this endpoint with JWT verification disabled at the Supabase gateway because the function validates Google's OIDC JWT itself. Keep `platform-admin` and `verify-play-purchase` JWT verification enabled.
