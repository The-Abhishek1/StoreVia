import { createClient, type User } from "npm:@supabase/supabase-js@2";

export const supabaseUrl = Deno.env.get("SUPABASE_URL")!;
export const service = createClient(supabaseUrl, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!, {
  auth: { autoRefreshToken: false, persistSession: false },
});

export const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};

export function json(data: unknown, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: { ...corsHeaders, "Content-Type": "application/json" },
  });
}

export async function authenticatedUser(request: Request): Promise<User> {
  const authorization = request.headers.get("Authorization");
  if (!authorization?.startsWith("Bearer ")) throw new Error("Sign in required.");
  const { data, error } = await service.auth.getUser(authorization.slice(7));
  if (error || !data.user) throw new Error("Invalid session.");
  return data.user;
}

export async function requirePlatformAdmin(user: User) {
  const { data, error } = await service.from("platform_admins").select("user_id").eq("user_id", user.id).maybeSingle();
  if (error || !data) throw new Error("Platform administrator access required.");
}

export async function writeAdminAudit(actorId: string, action: string, storeId: string | null, summary: string) {
  const { error } = await service.from("platform_admin_audit").insert({
    actor_id: actorId,
    action,
    store_id: storeId,
    summary,
  });
  if (error) throw error;
}