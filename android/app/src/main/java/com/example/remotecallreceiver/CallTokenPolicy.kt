package com.example.remotecallreceiver

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal object CallTokenPolicy {
    fun isConfigured(token: String): Boolean = token.isNotBlank() && token.trim() != "dev-token"

    fun matches(expected: String, provided: String?): Boolean =
        isConfigured(expected) && provided != null && MessageDigest.isEqual(
            expected.toByteArray(StandardCharsets.UTF_8),
            provided.toByteArray(StandardCharsets.UTF_8)
        )
}
