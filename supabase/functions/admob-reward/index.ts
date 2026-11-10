import { authenticatedUser, json, service } from "../_shared/platform.ts";

const REWARDED_AD_UNIT = "6868274527";
const VERIFIER_KEYS_URL = "https://www.gstatic.com/admob/reward/verifier-keys.json";

type VerifierKey = { keyId: number; base64: string };

class InvalidCallbackError extends Error {}

function decodeBase64(value: string): Uint8Array {
  const normalized = value.replaceAll("-", "+").replaceAll("_", "/");
  const padded = normalized.padEnd(Math.ceil(normalized.length / 4) * 4, "=");
  try {
    return Uint8Array.from(atob(padded), (char) => char.charCodeAt(0));
  } catch {
    throw new InvalidCallbackError("Invalid AdMob signature encoding.");
  }
}

function derToP1363(signature: Uint8Array): Uint8Array {
  let offset = 0;
  if (signature[offset++] !== 0x30) throw new Error("Invalid AdMob signature.");
  const sequenceLength = signature[offset++];
  if (sequenceLength !== signature.length - offset) throw new Error("Invalid AdMob signature.");

  const readInteger = () => {
    if (signature[offset++] !== 0x02) throw new Error("Invalid AdMob signature.");
    const length = signature[offset++];
    let integer = signature.slice(offset, offset + length);
    offset += length;
    while (integer.length > 32 && integer[0] === 0) integer = integer.slice(1);
    if (integer.length > 32) throw new Error("Invalid AdMob signature.");
    const padded = new Uint8Array(32);
    padded.set(integer, 32 - integer.length);
    return padded;
  };

  const r = readInteger();
  const s = readInteger();
  if (offset !== signature.length) throw new Error("Invalid AdMob signature.");
  const raw = new Uint8Array(64);
  raw.set(r);
  raw.set(s, 32);
  return raw;
}

async function verifyCallback(request: Request): Promise<URLSearchParams> {
  const requestUrl = new URL(request.url);
  const rawQuery = requestUrl.search.slice(1);
  const signatureIndex = rawQuery.indexOf("signature=");
  if (signatureIndex <= 0) throw new InvalidCallbackError("Missing AdMob signature.");
  const signedContent = rawQuery.slice(0, signatureIndex - 1);
  const params = requestUrl.searchParams;
  const keyId = Number(params.get("key_id"));
  const signature = params.get("signature");
  if (!Number.isSafeInteger(keyId) || !signature || !signedContent) {
    throw new InvalidCallbackError("Invalid AdMob signature parameters.");
  }

  const response = await fetch(VERIFIER_KEYS_URL);
  if (!response.ok) throw new Error("Could not retrieve AdMob verification keys.");
  const body = await response.json() as { keys: VerifierKey[] };
  const verifier = body.keys.find((key) => key.keyId === keyId);
  if (!verifier) throw new InvalidCallbackError("Unknown AdMob verification key.");

  const publicKey = await crypto.subtle.importKey(
    "spki",
    decodeBase64(verifier.base64),
    { name: "ECDSA", namedCurve: "P-256" },
    false,
    ["verify"],
  );
  let valid: boolean;
  try {
    valid = await crypto.subtle.verify(
      { name: "ECDSA", hash: "SHA-256" },
      publicKey,
      derToP1363(decodeBase64(signature)),
      new TextEncoder().encode(signedContent),
    );
  } catch {
    throw new InvalidCallbackError("Invalid AdMob signature.");
  }
  if (!valid) throw new InvalidCallbackError("AdMob callback signature is invalid.");
  return params;
}

async function issueRewardRequest(request: Request): Promise<Response> {
  const user = await authenticatedUser(request);
  const body = await request.json();
  const storeId = String(body.storeId ?? "").trim();
  if (!storeId) return json({ error: "Connect a Cloud store first." }, 400);

  const { data: membership, error: membershipError } = await service.from("store_members")
    .select("store_id").eq("store_id", storeId).eq("user_id", user.id).maybeSingle();
  if (membershipError) throw membershipError;
  if (!membership) return json({ error: "You are not a member of this Cloud store." }, 403);

  const now = new Date();
  const { data: pending, error: pendingError } = await service.from("ad_reward_nonces")
    .select("nonce")
    .eq("store_id", storeId)
    .eq("user_id", user.id)
    .is("used_at", null)
    .gt("expires_at", now.toISOString())
    .limit(1)
    .maybeSingle();
  if (pendingError) throw pendingError;
  if (pending?.nonce) return json({ customData: pending.nonce });

  const { error: cleanupError } = await service.from("ad_reward_nonces")
    .delete().eq("store_id", storeId).eq("user_id", user.id).is("used_at", null)
    .lte("expires_at", now.toISOString());
  if (cleanupError) throw cleanupError;

  const nonce = crypto.randomUUID();
  const { error: insertError } = await service.from("ad_reward_nonces").insert({
    nonce,
    store_id: storeId,
    user_id: user.id,
    expires_at: new Date(now.getTime() + 30 * 60 * 1000).toISOString(),
  });
  if (insertError) throw insertError;
  return json({ customData: nonce });
}

async function grantVerifiedReward(request: Request): Promise<Response> {
  const params = await verifyCallback(request);
  const adUnit = params.get("ad_unit");
  const transactionId = params.get("transaction_id");
  const nonce = params.get("custom_data");
  if (
    adUnit !== REWARDED_AD_UNIT ||
    !transactionId
  ) {
    throw new InvalidCallbackError("Invalid reward callback.");
  }

  if (!nonce) return new Response("OK", { status: 200 });

  const { data: rewardRequest, error: requestError } = await service.from("ad_reward_nonces")
    .select("nonce")
    .eq("nonce", nonce)
    .maybeSingle();
  if (requestError) throw requestError;
  if (!rewardRequest) return new Response("OK", { status: 200 });

  const { error } = await service.rpc("storevia_grant_ad_reward", {
    p_transaction_id: transactionId,
    p_nonce: nonce,
  });
  if (error) throw error;
  return new Response("OK", { status: 200 });
}

Deno.serve(async (request) => {
  if (request.method === "OPTIONS") return json({ ok: true });

  try {
    if (request.method === "POST") return await issueRewardRequest(request);
    if (request.method === "GET") return await grantVerifiedReward(request);
    return json({ error: "Method not allowed." }, 405);
  } catch (error) {
    const message = error instanceof Error ? error.message : "Reward verification failed.";
    const status = request.method === "GET"
      ? error instanceof InvalidCallbackError ? 200 : 500
      : message.includes("Sign in") || message.includes("Invalid session") ? 401 : 400;
    return request.method === "GET"
      ? new Response(status === 200 ? "OK" : "Reward verification failed.", { status })
      : json({ error: message }, status);
  }
});
