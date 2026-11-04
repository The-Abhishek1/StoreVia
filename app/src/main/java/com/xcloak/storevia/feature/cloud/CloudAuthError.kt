package com.xcloak.storevia.feature.cloud

internal object CloudAuthError {
    fun message(error: Throwable): String? {
        val chain = generateSequence(error) { it.cause }.take(16).toList()
        val messages = chain.mapNotNull { it.message }
        val networkExceptionNames = setOf(
            "UnknownHostException",
            "UnresolvedAddressException",
            "ConnectException",
            "ConnectTimeoutException",
            "SocketTimeoutException",
            "SocketException",
            "NoRouteToHostException",
            "HttpRequestTimeoutException",
        )
        if (chain.any { it.javaClass.simpleName in networkExceptionNames } || messages.any(::looksLikeConnectionFailure)) {
            return "No internet connection. Connect to the internet and try signing in again."
        }

        if (messages.any {
                it.contains("invalid login credentials", ignoreCase = true) ||
                    it.contains("invalid_credentials", ignoreCase = true) ||
                    it.contains("email or password is incorrect", ignoreCase = true)
            }
        ) {
            return "The email/mobile number or password is incorrect."
        }
        if (messages.any { it.contains("email not confirmed", ignoreCase = true) }) {
            return "Verify your email address before signing in."
        }
        return null
    }

    private fun looksLikeConnectionFailure(message: String): Boolean {
        val normalized = message.lowercase()
        return listOf(
            "unable to resolve host",
            "unknownhostexception",
            "unresolvedaddressexception",
            "failed to connect",
            "connection timed out",
            "connect timed out",
            "connection refused",
            "network is unreachable",
            "no address associated with hostname",
            "socket time out",
            "sockettimeout",
            "no internet connection",
        ).any(normalized::contains)
    }
}
