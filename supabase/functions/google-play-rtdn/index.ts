import { json, service } from "../_shared/platform.ts";

const PACKAGE_NAME = Deno.env.get("PLAY_PACKAGE_NAME")!;
const PRODUCT_ID = Deno.env.get("PLAY_PRO_PRODUCT_ID")!;
const SERVICE_ACCOUNT_JSON = Deno.env.get("GOOGLE_PLAY_SERVICE_ACCOUNT_JSON")!;
const EXPECTED_PUSH_EMAIL = Deno.env.get("PUBSUB_PUSH_SERVICE_ACCOUNT_EMAIL")!;
const EXPECTED_AUDIENCE = Deno.env.get("PUBSUB_PUSH_AUDIENCE")!;
const TOKEN_URL = "https://oauth2.googleapis.com/token";
const PUBLISHER_SCOPE = "https://www.googleapis.com/auth/androidpublisher";
let cachedKeys: JsonWebKey[] | null = null;

function decodeBase64Url(value: string) {
  const normalized = value.replaceAll("-", "+").replaceAll("_", "/");
  return Uint8Array.from(atob(normalized.padEnd(Math.ceil(normalized.length / 4) * 4, "=")), (char) => char.charCodeAt(0));
}

function decodeJson<T>(value: string): T {
  return JSON.parse(new TextDecoder().decode(decodeBase64Url(value))) as T;
}

function base64Url(bytes: Uint8Array) {
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replaceAll("+", "-").replaceAll("/", "_").replaceAll("=", "");
}

function jsonSegment(value: unknown) {
  return base64Url(new TextEncoder().encode(JSON.stringify(value)));
}

async function validatePushToken(request: Request) {
  const authorization = request.headers.get("Authorization");
  if (!authorization?.startsWith("Bearer ")) throw new Error("Missing Pub/Sub OIDC token.");
  const jwt = authorization.slice(7);
  const [headerPart, claimsPart, signaturePart] = jwt.split(".");
  if (!headerPart || !claimsPart || !signaturePart) throw new Error("Malformed Pub/Sub OIDC token.");
  const header = decodeJson<{ alg?: string; kid?: string }>(headerPart);
  const claims = decodeJson<{ iss?: string; aud?: string | string[]; exp?: number; email?: string; email_verified?: boolean }>(claimsPart);
  if (header.alg !== "RS256" || !header.kid || !claims.exp || claims.exp <= Date.now() / 1000) throw new Error("Expired or unsupported Pub/Sub token.");
  const audienceMatches = Array.isArray(claims.aud) ? claims.aud.includes(EXPECTED_AUDIENCE) : claims.aud === EXPECTED_AUDIENCE;
  if (!audienceMatches || !["accounts.google.com", "https://accounts.google.com"].includes(claims.iss ?? "") ||
      claims.email !== EXPECTED_PUSH_EMAIL || claims.email_verified !== true) throw new Error("Pub/Sub identity is not authorized.");

  if (!cachedKeys) {
    const response = await fetch("https://www.googleapis.com/oauth2/v3/certs");
    if (!response.ok) throw new Error("Could not load Google signing keys.");
    const jwks = await response.json();
    cachedKeys = jwks.keys as JsonWebKey[];
  }
  const jwk = cachedKeys.find((key) => (key as JsonWebKey & { kid?: string }).kid === header.kid);
  if (!jwk) throw new Error("Unknown Pub/Sub signing key.");
  const key = await crypto.subtle.importKey("jwk", jwk, { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["verify"]);
  const valid = await crypto.subtle.verify(
    "RSASSA-PKCS1-v1_5",
    key,
    decodeBase64Url(signaturePart),
    new TextEncoder().encode(`${headerPart}.${claimsPart}`),
  );
  if (!valid) throw new Error("Invalid Pub/Sub signature.");
}

function pemBytes(pem: string) {
  const body = pem.replace(/-----BEGIN PRIVATE KEY-----|-----END PRIVATE KEY-----|\s/g, "");
  return Uint8Array.from(atob(body), (char) => char.charCodeAt(0));
}

async function publisherToken() {
  const account = JSON.parse(SERVICE_ACCOUNT_JSON);
  const now = Math.floor(Date.now() / 1000);
  const unsigned = `${jsonSegment({ alg: "RS256", typ: "JWT" })}.${jsonSegment({
    iss: account.client_email, scope: PUBLISHER_SCOPE, aud: TOKEN_URL, iat: now, exp: now + 3600,
  })}`;
  const key = await crypto.subtle.importKey("pkcs8", pemBytes(account.private_key), { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["sign"]);
  const signature = new Uint8Array(await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, new TextEncoder().encode(unsigned)));
  const response = await fetch(TOKEN_URL, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({ grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer", assertion: `${unsigned}.${base64Url(signature)}` }),
  });
  if (!response.ok) throw new Error("Could not authorize Google Play lookup.");
  return String((await response.json()).access_token);
}

async function sha256Hex(value: string) {
  const bytes = new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value)));
  return Array.from(bytes, (byte) => byte.toString(16).padStart(2, "0")).join("");
}

Deno.serve(async (request) => {
  if (request.method !== "POST") return json({ error: "Method not allowed." }, 405);
  try {
    await validatePushToken(request);
    const envelope = await request.json();
    const encoded = envelope?.message?.data;
    if (typeof encoded !== "string") return json({ error: "Missing Pub/Sub message data." }, 400);
    const notification = JSON.parse(new TextDecoder().decode(Uint8Array.from(atob(encoded), (char) => char.charCodeAt(0))));
    if (notification.packageName !== PACKAGE_NAME) return json({ error: "Unexpected package name." }, 400);
    const purchaseToken = notification.subscriptionNotification?.purchaseToken;
    if (!purchaseToken) return json({ ok: true, ignored: true });

    const purchaseTokenHash = await sha256Hex(purchaseToken);
    const { data: subscription, error: lookupError } = await service.from("subscriptions")
      .select("store_id").eq("purchase_token_hash", purchaseTokenHash).maybeSingle();
    if (lookupError) throw lookupError;
    if (!subscription) return json({ ok: true, ignored: true });

    const accessToken = await publisherToken();
    const endpoint = `https://androidpublisher.googleapis.com/androidpublisher/v3/applications/${encodeURIComponent(PACKAGE_NAME)}/purchases/subscriptionsv2/tokens/${encodeURIComponent(purchaseToken)}`;
    const response = await fetch(endpoint, { headers: { Authorization: `Bearer ${accessToken}` } });
    if (!response.ok) throw new Error("Google Play purchase refresh failed.");
    const purchase = await response.json();
    const item = (purchase.lineItems ?? []).find((line: { productId?: string }) => line.productId === PRODUCT_ID);
    const expiresAt = item?.expiryTime ? new Date(item.expiryTime).toISOString() : new Date().toISOString();
    const state = String(purchase.subscriptionState ?? "");
    const accessStates = new Set(["SUBSCRIPTION_STATE_ACTIVE", "SUBSCRIPTION_STATE_IN_GRACE_PERIOD", "SUBSCRIPTION_STATE_CANCELED"]);
    const canAccess = !!item && accessStates.has(state) && new Date(expiresAt).getTime() > Date.now();
    const orderId = item?.latestSuccessfulOrderId;

    if (item && orderId) {
      const { error: paymentError } = await service.from("platform_payment_records").upsert({
        store_id: subscription.store_id,
        amount_minor: null,
        currency: "INR",
        provider: "google_play",
        product_id: PRODUCT_ID,
        external_reference: orderId,
        status: notification.subscriptionNotification?.notificationType === 12 ? "refunded" : "verified",
        note: "Google Play subscription lifecycle event.",
      }, { onConflict: "provider,external_reference", ignoreDuplicates: true });
      if (paymentError) throw paymentError;
      if (notification.subscriptionNotification?.notificationType === 12) {
        await service.from("platform_payment_records").update({ status: "refunded" }).eq("provider", "google_play").eq("external_reference", orderId);
      }
    }

    const { error: updateError } = await service.from("subscriptions").update({
      plan: canAccess ? "pro" : "free",
      status: "active",
      expires_at: canAccess ? expiresAt : "9999-12-31T23:59:59Z",
      updated_at: new Date().toISOString(),
    }).eq("store_id", subscription.store_id);
    if (updateError) throw updateError;
    return json({ ok: true, state, status: canAccess ? "active" : "expired" });
  } catch (error) {
    const message = error instanceof Error ? error.message : "Notification processing failed.";
    return json({ error: message }, message.includes("Pub/Sub") || message.includes("identity") ? 401 : 400);
  }
});