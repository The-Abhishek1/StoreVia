import { authenticatedUser, json, requirePlatformAdmin, service, writeAdminAudit } from "../_shared/platform.ts";

Deno.serve(async (request) => {
  if (request.method === "OPTIONS") return json({ ok: true });
  if (request.method !== "POST") return json({ error: "Method not allowed." }, 405);

  try {
    const user = await authenticatedUser(request);
    await requirePlatformAdmin(user);
    const body = await request.json();
    const action = String(body.action ?? "");

    if (action === "access_check") return json({ ok: true });

    if (action === "overview") {
      const [plans, storesResult, paymentsResult] = await Promise.all([
        service.from("subscriptions").select("store_id,plan,status,expires_at,product_id,updated_at").order("updated_at", { ascending: false }),
        service.from("store").select("id,store_id,name,address,phone,gstin,state_code,created_at,updated_at"),
        service.from("platform_payment_records").select("id,store_id,amount_minor,currency,provider,product_id,external_reference,status,note,created_at").order("created_at", { ascending: false }).limit(200),
      ]);
      if (plans.error) throw plans.error;
      if (storesResult.error) throw storesResult.error;
      if (paymentsResult.error) throw paymentsResult.error;
      const metadata = await service.from("platform_store_metadata").select("store_id,store_type");
      if (metadata.error) throw metadata.error;
      const metaById = new Map((metadata.data ?? []).map((item) => [item.store_id, item.store_type]));
      const storeById = new Map((storesResult.data ?? []).map((store) => [store.store_id, store]));
      const stores = (plans.data ?? []).map((plan) => ({
        ...plan,
        store: storeById.get(plan.store_id) ?? null,
        store_type: metaById.get(plan.store_id) ?? "GENERAL",
      }));
      const amountMinor = (paymentsResult.data ?? []).reduce((sum, payment) => sum + (payment.amount_minor ?? 0), 0);
      return json({ stores, payments: paymentsResult.data ?? [], insights: {
        total: stores.length,
        active: stores.filter((store) => store.status === "active").length,
        held: stores.filter((store) => store.status === "held").length,
        pro: stores.filter((store) => store.plan === "pro" && store.status === "active").length,
        recordedAmountMinor: amountMinor,
      } });
    }

    if (action === "create_store") {
      const name = String(body.name ?? "").trim();
      const ownerEmail = String(body.ownerEmail ?? "").trim().toLowerCase();
      const storeType = String(body.storeType ?? "GENERAL");
      if (!name || !ownerEmail || !["GENERAL", "MEDICAL", "WHOLESALER"].includes(storeType)) {
        return json({ error: "Store name, valid owner email, and store type are required." }, 400);
      }
      const storeId = crypto.randomUUID();
      const { error } = await service.rpc("platform_create_store", {
        p_store_id: storeId,
        p_name: name,
        p_owner_email: ownerEmail,
        p_store_type: storeType,
        p_actor_id: user.id,
      });
      if (error) throw error;
      return json({ storeId });
    }

    const storeId = String(body.storeId ?? "");
    if (!storeId) return json({ error: "Store ID is required." }, 400);

    if (action === "update_store") {
      const name = String(body.name ?? "").trim();
      const storeType = String(body.storeType ?? "GENERAL");
      if (!name || !["GENERAL", "MEDICAL", "WHOLESALER"].includes(storeType)) return json({ error: "Valid name and store type are required." }, 400);
      const { error } = await service.from("store").update({
        name, address: String(body.address ?? ""), phone: String(body.phone ?? ""),
        gstin: body.gstin ? String(body.gstin) : null, updated_at: Date.now(),
      }).eq("id", storeId).eq("store_id", storeId);
      if (error) throw error;
      const { error: metaError } = await service.from("platform_store_metadata").upsert({ store_id: storeId, store_type: storeType, updated_at: new Date().toISOString() });
      if (metaError) throw metaError;
      await writeAdminAudit(user.id, "STORE_UPDATED", storeId, `Updated store ${name}.`);
      return json({ ok: true });
    }

    if (action === "hold_store" || action === "restore_store" || action === "delete_store") {
      const status = action === "hold_store" ? "held" : action === "delete_store" ? "deleted" : "active";
      const { error } = await service.from("subscriptions").update({ status, updated_at: new Date().toISOString() }).eq("store_id", storeId);
      if (error) throw error;
      await writeAdminAudit(user.id, action.toUpperCase(), storeId, `Set store status to ${status}.`);
      return json({ ok: true, status });
    }

    if (action === "record_payment") {
      const amountMinor = Number(body.amountMinor);
      const note = String(body.note ?? "").trim();
      if (!Number.isSafeInteger(amountMinor) || amountMinor <= 0 || !note) return json({ error: "Enter a positive amount and payment note." }, 400);
      const reference = crypto.randomUUID();
      const { error } = await service.from("platform_payment_records").insert({
        store_id: storeId, amount_minor: amountMinor, currency: "INR", provider: "manual",
        external_reference: reference, status: "recorded", note, recorded_by: user.id,
      });
      if (error) throw error;
      await writeAdminAudit(user.id, "PAYMENT_RECORDED", storeId, `Recorded INR ${amountMinor} minor units: ${note}`);
      return json({ ok: true, reference });
    }

    return json({ error: "Unknown action." }, 400);
  } catch (error) {
    const message = error instanceof Error ? error.message : "Request failed.";
    const status = message.includes("Sign in") || message.includes("Invalid session") ? 401 : message.includes("administrator access") ? 403 : 400;
    return json({ error: message }, status);
  }
});