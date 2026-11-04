package com.xcloak.storevia.feature.cloud

internal object CloudConnectionError {
    fun message(error: Throwable): String {
        CloudAuthError.message(error)?.let { return it }

        val chain = generateSequence(error) { it.cause }.take(16).toList()
        val messages = chain.mapNotNull { it.message }
        val combined = messages.joinToString(" ").lowercase()
        return when {
            "schema cache" in combined ||
                "could not find the function" in combined ||
                "pgrst202" in combined ->
                "Cloud setup is outdated. Apply the latest supabase/schema.sql in the Supabase SQL Editor, then try again."

            "jwt expired" in combined ||
                "refresh token" in combined ||
                "session_not_found" in combined ->
                "Your cloud session has expired. Sign out, then sign in again."

            "permission denied" in combined ||
                "row-level security" in combined ||
                "violates row-level security" in combined ->
                "Supabase denied store access. Check that this account belongs to the store and the latest Supabase policies are applied."

            "this account is linked to a different store" in combined ->
                "This account is linked to a different store than before. Local data was not changed."

            "this account belongs to a different store" in combined ->
                "This account belongs to a different store. Back up this phone and restore that store's data before connecting. Local data was not changed."

            else -> diagnosticMessage(messages.firstOrNull())
        }
    }

    private fun diagnosticMessage(detail: String?): String {
        if (detail.isNullOrBlank()) {
            return "Could not connect to the cloud store. Check your internet connection and retry."
        }
        val safeDetail = detail
            .replace(Regex("(?i)(access[_ -]?token|refresh[_ -]?token|authorization|apikey|api[_ -]?key)(\\s*[:=]\\s*)[^\\s,;}]+"), "$1$2[hidden]")
            .replace(Regex("https?://\\S+"), "[cloud endpoint]")
            .replace(Regex("\\s+"), " ")
            .take(180)
        return "Could not connect to the cloud store: $safeDetail"
    }
}
