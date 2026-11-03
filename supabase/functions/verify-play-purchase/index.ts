import { authenticatedUser, json, service } from "../_shared/platform.ts";

const PACKAGE_NAME = Deno.env.get("PLAY_PACKAGE_NAME")!;
const PRODUCT_ID = Deno.env.get("PLAY_PRO_PRODUCT_ID")!;
const SERVICE_ACCOUNT_JSON = Deno.env.get("GOOGLE_PLAY_SERVICE_ACCOUNT_JSON")!;
const TOKEN_URL = "https://oauth2.googleapis.com/token";
const PUBLISHER_SCOPE = "https://www.googleapis.com/auth/androidpublisher";

function base64Url(bytes: Uint8Array) {
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replaceAll("+", "-").replaceAll("/", "_").replaceAll("=", "");
}

function jsonSegment(value: unknown) {
  return base64Url(new TextEncoder().encode(JSON.stringify(value)));
}

function pemBytes(pem: string) {
  const body = pem.replace(/-----BEGIN PRIVATE KEY-----|-----END PRIVATE KEY-----|\s/g, "");
  return Uint8Array.from(atob(body), (char) => char.charCodeAt(0));
}

async function publisherToken() {
  const account = JSON.parse(SERVICE_ACCOUNT_JSON);
  const now = Math.floor(Date.now() / 1000);
  const unsigned = `${jsonSegment({ alg: "RS256", typ: "JWT" })}.${jsonSegment({
    iss: account.client_email,
    scope: PUBLISHER_SCOPE,
    aud: TOKEN_URL,
    iat: now,
    exp: now + 3600,
  })}`;
  const key = await crypto.subtle.importKey(
    "pkcs8",
    pemBytes(account.private_key),
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const signature = new Uint8Array(await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, new TextEncoder().encode(unsigned)));
  const response = await fetch(TOKEN_URL, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({ grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer", assertion: `${unsigned}.${base64Url(signature)}` }),
  });
  if (!response.ok) throw new Error("Could not authorize Google Play verification.");
  const body = await response.json();
  return String(body.access_token);
}

async function sha256Hex(value: string) {
  const bytes = new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value)));
  return Array.from(bytes, (byte) => byte.toString(16).padStart(2, "0")).join("");
}

Deno.serve(async (request) => {
  if (request.method === "OPTIONS") return json({ ok: true });
  if (request.method !== "POST") return json({ error: "Method not allowed." }, 405);

  try {
    const user = await authenticatedUser(request);
    const body = await request.json();
    const storeId = String(body.storeId ?? "");
    const purchaseToken = String(body.purchaseToken ?? "");
    if (!storeId || !purchaseToken || !PACKAGE_NAME || !PRODUCT_ID || !SERVICE_ACCOUNT_JSON) {
      return json({ error: "Purchase verification is not configured or request fields are missing." }, 400);
    }
    const { data: membership, error: memberError } = await service.from("store_members")
      .select("role").eq("store_id", storeId).eq("user_id", user.id).maybeSingle();
    if (memberError || membership?.role !== "OWNER") return json({ error: "Only the store owner can upgrade the plan." }, 403);

    const token = await publisherToken();
    const endpoint = `https://androidpublisher.googleapis.com/androidpublisher/v3/applications/${encodeURIComponent(PACKAGE_NAME)}/purchases/subscriptionsv2/tokens/${encodeURIComponent(purchaseToken)}`;
    const verification = await fetch(endpoint, { headers: { Authorization: `Bearer ${token}` } });
    if (!verification.ok) return json({ error: "Google Play could not verify this purchase." }, 400);
    const purchase = await verification.json();
    const line = (purchase.lineItems ?? []).find((item: { productId?: string }) => item.productId === PRODUCT_ID);
    const expectedAccountId = await sha256Hex(storeId);
    const linkedAccountId = purchase.externalAccountIdentifiers?.obfuscatedExternalAccountId;
    const activeStates = new Set(["SUBSCRIPTION_STATE_ACTIVE", "SUBSCRIPTION_STATE_IN_GRACE_PERIOD"]);
    if (!line || !activeStates.has(String(purchase.subscriptionState)) || linkedAccountId !== expectedAccountId || !line.expiryTime) {
      return json({ error: "Purchase is inactive or is not linked to this store." }, 400);
    }

    const orderId = String(line.latestSuccessfulOrderId ?? purchase.latestOrderId ?? await sha256Hex(purchaseToken));
    const expiresAt = new Date(line.expiryTime).toISOString();
    const purchaseTokenHash = await sha256Hex(purchaseToken);
    const { error: planError } = await service.from("subscriptions").update({
      plan: "pro", status: "active", expires_at: expiresAt, product_id: PRODUCT_ID,
      purchase_token: null, purchase_token_hash: purchaseTokenHash, updated_at: new Date().toISOString(),
    }).eq("store_id", storeId);
    if (planError) throw planError;

    const { error: paymentError } = await service.from("platform_payment_records").upsert({
      store_id: storeId, amount_minor: null, currency: "INR", provider: "google_play",
      product_id: PRODUCT_ID, external_reference: orderId, status: "verified", note: "Google Play subscription verified.",
    }, { onConflict: "provider,external_reference", ignoreDuplicates: true });
    if (paymentError) throw paymentError;

    if (purchase.acknowledgementState !== "ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED") {
      const acknowledgeEndpoint = `https://androidpublisher.googleapis.com/androidpublisher/v3/applications/${encodeURIComponent(PACKAGE_NAME)}/purchases/subscriptions/${encodeURIComponent(PRODUCT_ID)}/tokens/${encodeURIComponent(purchaseToken)}:acknowledge`;
      const acknowledge = await fetch(acknowledgeEndpoint, {
        method: "POST",
        headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
        body: JSON.stringify({ developerPayload: "storevia" }),
      });
      if (!acknowledge.ok) throw new Error("Purchase was verified but acknowledgement failed; retry verification.");
    }

    return json({ plan: "pro", status: "active", expiresAt });
  } catch (error) {
    const message = error instanceof Error ? error.message : "Purchase verification failed.";
    const status = message.includes("Sign in") || message.includes("Invalid session") ? 401 : 400;
    return json({ error: message }, status);
  }
});