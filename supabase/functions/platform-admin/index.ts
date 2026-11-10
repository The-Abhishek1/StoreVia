import { authenticatedUser, json, requirePlatformAdmin, service, writeAdminAudit } from "../_shared/platform.ts";

type AccountSubscription = {
  store_id: string;
  store_name: string;
  plan: string;
  status: string;
  expires_at: string | null;
};

type DeletionScope = {
  target_kind: "account" | "store";
  target_id: string;
  target_name: string;
  confirmation_text: string;
  users: Array<{ user_id: string; email: string }>;
  user_ids: string[];
  stores: Array<{ store_id: string; name: string }>;
  store_ids: string[];
  row_counts: Record<string, number>;
  total_rows: number;
  cloud_backup_count: number;
  cloud_backup_store_ids: string[];
};

async function includeCloudBackupImpact(scope: DeletionScope): Promise<DeletionScope> {
  const cloudBackupStoreIds: string[] = [];
  for (const storeId of scope.store_ids) {
    const { data, error } = await service.storage.from("storevia-backups")
      .list(storeId, { limit: 100, search: "latest.svbackup" });
    if (error) throw new Error(`Could not check the encrypted backup for store ${storeId}: ${error.message}`);
    if (data?.some((item) => item.name === "latest.svbackup")) cloudBackupStoreIds.push(storeId);
  }
  return {
    ...scope,
    cloud_backup_count: cloudBackupStoreIds.length,
    cloud_backup_store_ids: cloudBackupStoreIds,
  };
}

Deno.serve(async (request) => {
  if (request.method === "OPTIONS") return json({ ok: true });
  if (request.method !== "POST") return json({ error: "Method not allowed." }, 405);

  try {
    const user = await authenticatedUser(request);
    await requirePlatformAdmin(user);
    const body = await request.json();
    const action = String(body.action ?? "");

    if (action === "access_check") return json({ ok: true });

    if (action === "deletion_preview" || action === "delete_scope") {
      const targetKind = String(body.targetKind ?? "");
      const targetId = String(body.targetId ?? "").trim();
      if (!["account", "store"].includes(targetKind) || !targetId) {
        return json({ error: "Choose a valid account or store." }, 400);
      }

      const { data: rawScope, error: previewError } = await service.rpc(
        "platform_admin_deletion_preview",
        { p_target_kind: targetKind, p_target_id: targetId },
      );
      if (previewError) throw previewError;
      const scope = await includeCloudBackupImpact(rawScope as DeletionScope);
      if (action === "deletion_preview") return json(scope);

      const confirmationText = String(body.confirmationText ?? "");
      if (confirmationText !== scope.confirmation_text) {
        return json({ error: "Confirmation text does not match the impact preview." }, 400);
      }
      const expectedUserIds = Array.isArray(body.expectedUserIds) ? body.expectedUserIds.map(String) : [];
      const expectedStoreIds = Array.isArray(body.expectedStoreIds) ? body.expectedStoreIds.map(String) : [];
      const expectedRowCounts = body.expectedRowCounts && typeof body.expectedRowCounts === "object"
        ? body.expectedRowCounts as Record<string, number>
        : {};
      const sameList = (left: string[], right: string[]) => {
        const sortedLeft = [...left].sort();
        const sortedRight = [...right].sort();
        return sortedLeft.length === sortedRight.length &&
          sortedLeft.every((value, index) => value === sortedRight[index]);
      };
      const expectedCountKeys = Object.keys(expectedRowCounts).sort();
      const currentCountKeys = Object.keys(scope.row_counts).sort();
      const sameCounts = sameList(expectedCountKeys, currentCountKeys) &&
        currentCountKeys.every((key) => Number(expectedRowCounts[key]) === Number(scope.row_counts[key]));
      if (
        !sameList(expectedUserIds, scope.user_ids) ||
        !sameList(expectedStoreIds, scope.store_ids) ||
        !sameCounts ||
        Number(body.expectedCloudBackupCount) !== scope.cloud_backup_count ||
        Number(body.expectedTotalRows) !== Number(scope.total_rows)
      ) {
        return json({ error: "The deletion impact changed. Refresh the preview and confirm again." }, 409);
      }
      if (scope.user_ids.includes(user.id)) {
        return json({ error: "You cannot delete the currently signed-in platform admin account." }, 400);
      }

      for (const storeId of scope.cloud_backup_store_ids) {
        const { error } = await service.storage.from("storevia-backups")
          .remove([`${storeId}/latest.svbackup`]);
        if (error) throw new Error(`Could not remove the encrypted backup for store ${storeId}: ${error.message}`);
      }

      const { data: deleted, error: deletionError } = await service.rpc(
        "platform_admin_execute_deletion",
        {
          p_target_kind: targetKind,
          p_target_id: targetId,
          p_confirmation_text: confirmationText,
          p_expected_user_ids: scope.user_ids,
          p_expected_store_ids: scope.store_ids,
          p_expected_row_counts: scope.row_counts,
        },
      );
      if (deletionError) throw deletionError;

      const failedUsers: Array<{ user_id: string; email: string; error: string }> = [];
      for (const member of scope.users) {
        const { error } = await service.auth.admin.deleteUser(member.user_id);
        if (error) failedUsers.push({ ...member, error: error.message });
      }

      await writeAdminAudit(
        user.id,
        targetKind === "account" ? "ACCOUNT_DELETED" : "STORE_DELETED",
        targetKind === "store" ? targetId : null,
        `Deleted ${scope.users.length} cloud account(s), ${scope.stores.length} store(s), and ${scope.total_rows} associated record(s).${failedUsers.length ? ` Auth account deletion failed for ${failedUsers.length} user(s).` : ""}`,
      );
      return json({
        ok: true,
        deleted_user_count: scope.users.length - failedUsers.length,
        deleted_store_count: scope.stores.length,
        deleted_row_count: scope.total_rows,
        failed_users: failedUsers,
        database_scope: deleted,
      });
    }

    if (action === "overview") {
      const users: Array<{ id: string; email?: string | null; created_at: string }> = [];
      for (let page = 1; ; page++) {
        const { data, error } = await service.auth.admin.listUsers({ page, perPage: 1000 });
        if (error) throw error;
        users.push(...data.users);
        if (data.users.length < 1000) break;
      }

      const memberships: { user_id: string; store_id: string; role: string }[] = [];
      for (let offset = 0; ; offset += 1000) {
        const { data, error } = await service.from("store_members")
          .select("user_id,store_id,role")
          .order("user_id")
          .order("store_id")
          .range(offset, offset + 999);
        if (error) throw error;
        memberships.push(...(data ?? []));
        if (!data || data.length < 1000) break;
      }

      const usersById = new Map(users.map((account) => [account.id, account]));
      const missingUserIds = [...new Set(memberships.map((member) => member.user_id))]
        .filter((userId) => !usersById.has(userId));
      for (const userId of missingUserIds) {
        const { data, error } = await service.auth.admin.getUserById(userId);
        if (error) throw error;
        if (!data.user) throw new Error("Store membership references an unknown cloud account.");
        usersById.set(data.user.id, data.user);
      }

      const storeIds = [...new Set(memberships.map((member) => member.store_id))];
      const subscriptions = new Map<string, AccountSubscription>();
      const storeNames = new Map<string, string>();
      for (let offset = 0; offset < storeIds.length; offset += 500) {
        const ids = storeIds.slice(offset, offset + 500);
        const { data, error } = await service.from("subscriptions").select("store_id,plan,status,expires_at").in("store_id", ids);
        if (error) throw error;
        const { data: stores, error: storeError } = await service.from("store")
          .select("id,name")
          .in("id", ids);
        if (storeError) throw storeError;
        for (const store of stores ?? []) storeNames.set(store.id, store.name);
        for (const subscription of data ?? []) {
          subscriptions.set(subscription.store_id, {
            ...subscription,
            store_name: storeNames.get(subscription.store_id) ?? subscription.store_id,
          });
        }
      }

      const storeOwner = new Map<string, string>();
      for (const membership of memberships) {
        if (membership.role === "OWNER") storeOwner.set(membership.store_id, membership.user_id);
      }
      for (const membership of memberships) {
        if (!storeOwner.has(membership.store_id)) storeOwner.set(membership.store_id, membership.user_id);
      }

      const storesByUser = new Map<string, AccountSubscription[]>();
      for (const membership of memberships) {
        if (storeOwner.get(membership.store_id) !== membership.user_id) continue;
        const subscription = subscriptions.get(membership.store_id);
        if (!subscription) continue;
        const accountStores = storesByUser.get(membership.user_id) ?? [];
        if (!accountStores.some((item) => item.store_id === subscription.store_id)) {
          accountStores.push(subscription);
        }
        storesByUser.set(membership.user_id, accountStores);
      }
      return json({
        accounts: [...usersById.values()].map((account) => ({
          user_id: account.id,
          email: account.email ?? "",
          created_at: account.created_at,
          subscriptions: storesByUser.get(account.id) ?? [],
          store_membership_count: memberships.filter((member) => member.user_id === account.id).length,
        })),
      });
    }

    if (action === "set_plan") {
      const storeId = String(body.storeId ?? "").trim();
      const plan = String(body.plan ?? "").toLowerCase();
      if (!storeId || !["free", "pro"].includes(plan)) return json({ error: "A store and a valid plan are required." }, 400);
      const now = new Date();
      const expiresAt = new Date(now);
      expiresAt.setUTCFullYear(expiresAt.getUTCFullYear() + (plan === "pro" ? 1 : 100));
      const { data, error } = await service.from("subscriptions")
        .update({
          plan,
          status: "active",
          expires_at: expiresAt.toISOString(),
          updated_at: now.toISOString(),
        })
        .eq("store_id", storeId)
        .select("store_id,plan,status,expires_at")
        .maybeSingle();
      if (error) throw error;
      if (!data) return json({ error: "No subscription exists for this account." }, 404);
      await writeAdminAudit(
        user.id,
        "PLAN_CHANGED",
        storeId,
        `Set subscription plan to ${plan.toUpperCase()} for testing through ${data.expires_at}.`,
      );
      return json({
        ok: true,
        storeId: data.store_id,
        plan: data.plan,
        status: data.status,
        expires_at: data.expires_at,
      });
    }

    return json({ error: "Unknown action." }, 400);
  } catch (error) {
    const message = error instanceof Error ? error.message : "Request failed.";
    const status = message.includes("Sign in") || message.includes("Invalid session") ? 401 : message.includes("administrator access") ? 403 : 400;
    return json({ error: message }, status);
  }
});
