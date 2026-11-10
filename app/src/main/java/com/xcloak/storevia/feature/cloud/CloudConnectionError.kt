package com.xcloak.storevia.feature.cloud

internal object CloudConnectionError {
    fun message(error: Throwable): String {
        CloudAuthError.message(error)?.let { return it }

        val chain = generateSequence(error) { it.cause }.take(16).toList()
        val messages = chain.mapNotNull { it.message }
        val combined = messages.joinToString(" ").lowercase()
        val missingFunction = Regex(
            """could not find the function\s+((?:public\.)?[a-z_][a-z0-9_]*)""",
            RegexOption.IGNORE_CASE,
        ).find(combined)?.groupValues?.getOrNull(1)
        val diagnostic = sanitizedDetail(messages.joinToString(" | "))
        return when {
            missingFunction != null ->
                "Supabase could not resolve $missingFunction from its API schema cache. Check the function signature and API schema cache. Details: $diagnostic"

            "schema cache" in combined ||
                "pgrst202" in combined ->
                "Supabase returned an API schema-cache error. Details: $diagnostic"

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

            else -> diagnosticMessage(
                messages.asReversed().firstOrNull { message ->
                    message.isNotBlank() && !message.endsWith(" failed.", ignoreCase = true)
                } ?: messages.firstOrNull(),
            )
        }
    }

    private fun diagnosticMessage(detail: String?): String {
        if (detail.isNullOrBlank()) {
            return "Could not connect to the cloud store. Check your internet connection and retry."
        }
        return "Could not connect to the cloud store: ${sanitizedDetail(detail)}"
    }

    private fun sanitizedDetail(detail: String): String =
        detail
            .replace(Regex("(?i)bearer\\s+[A-Za-z0-9._~+/=-]+"), "Bearer [hidden]")
            .replace(Regex("(?i)(access[_ -]?token|refresh[_ -]?token|authorization|apikey|api[_ -]?key)(\\s*[:=]\\s*)[^\\s,;}]+"), "$1$2[hidden]")
            .replace(Regex("https?://\\S+"), "[cloud endpoint]")
            .replace(Regex("\\s+"), " ")
            .take(180)
}
