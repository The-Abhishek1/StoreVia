import { authenticatedUser, json, requirePlatformAdmin, service, writeAdminAudit } from "../_shared/platform.ts";

type AccountSubscription = {
  store_id: string;
  plan: string;
  status: string;
  expires_at: string | null;
};

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
      const users: Array<{ id: string; email?: string | null; created_at: string }> = [];
      for (let page = 1; ; page++) {
        const { data, error } = await service.auth.admin.listUsers({ page, perPage: 1000 });
        if (error) throw error;
        users.push(...data.users);
        if (data.users.length < 1000) break;
      }

      const memberships: { user_id: string; store_id: string }[] = [];
      for (let offset = 0; offset < users.length; offset += 500) {
        const ids = users.slice(offset, offset + 500).map((account) => account.id);
        if (ids.length === 0) continue;
        const { data, error } = await service.from("store_members").select("user_id,store_id").in("user_id", ids);
        if (error) throw error;
        memberships.push(...(data ?? []));
      }
      const storeIds = [...new Set(memberships.map((member) => member.store_id))];
      const subscriptions = new Map<string, AccountSubscription>();
      for (let offset = 0; offset < storeIds.length; offset += 500) {
        const ids = storeIds.slice(offset, offset + 500);
        const { data, error } = await service.from("subscriptions").select("store_id,plan,status,expires_at").in("store_id", ids);
        if (error) throw error;
        for (const subscription of data ?? []) subscriptions.set(subscription.store_id, subscription);
      }
      const storesByUser = new Map<string, AccountSubscription[]>();
      for (const membership of memberships) {
        const subscription = subscriptions.get(membership.store_id);
        if (!subscription) continue;
        const accountStores = storesByUser.get(membership.user_id) ?? [];
        if (!accountStores.some((item) => item.store_id === subscription.store_id)) accountStores.push(subscription);
        storesByUser.set(membership.user_id, accountStores);
      }
      return json({
        accounts: users.map((account) => ({
          user_id: account.id,
          email: account.email ?? "",
          created_at: account.created_at,
          subscriptions: storesByUser.get(account.id) ?? [],
        })),
      });
    }

    if (action === "set_plan") {
      const storeId = String(body.storeId ?? "").trim();
      const plan = String(body.plan ?? "").toLowerCase();
      if (!storeId || !["free", "pro"].includes(plan)) return json({ error: "A store and a valid plan are required." }, 400);
      const { data, error } = await service.from("subscriptions")
        .update({ plan, updated_at: new Date().toISOString() })
        .eq("store_id", storeId)
        .select("store_id")
        .maybeSingle();
      if (error) throw error;
      if (!data) return json({ error: "No subscription exists for this account." }, 404);
      await writeAdminAudit(user.id, "PLAN_CHANGED", storeId, `Set subscription plan to ${plan.toUpperCase()} for testing.`);
      return json({ ok: true, storeId, plan });
    }

    return json({ error: "Unknown action." }, 400);
  } catch (error) {
    const message = error instanceof Error ? error.message : "Request failed.";
    const status = message.includes("Sign in") || message.includes("Invalid session") ? 401 : message.includes("administrator access") ? 403 : 400;
    return json({ error: message }, status);
  }
});
