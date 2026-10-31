package com.vr2sbs.smb

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

data class SmbProfile(
    val id: String,
    val host: String,
    val share: String,
    val domain: String,
    val username: String,
    val password: String
) {
    val displayName: String
        get() = when {
            share.isBlank() && username.isBlank() -> host
            share.isBlank() -> "$username@$host"
            username.isBlank() -> "$host/$share"
            else -> "$username@$host/$share"
        }

    val rootUri: String
        get() = if (share.isBlank()) "smb://$host/" else "smb://$host/$share/"

    companion object {
        fun create(
            host: String,
            share: String,
            domain: String,
            username: String,
            password: String
        ): SmbProfile {
            val normalizedHost = host.trim()
            val normalizedShare = share.trim()
            val normalizedDomain = domain.trim()
            val normalizedUsername = username.trim()
            val identity = listOf(
                normalizedHost.lowercase(),
                normalizedShare.lowercase(),
                normalizedDomain.lowercase(),
                normalizedUsername.lowercase()
            ).joinToString("\u0000")
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(identity.toByteArray(StandardCharsets.UTF_8))
                .take(16)
                .joinToString("") { "%02x".format(it) }
            return SmbProfile(
                id = digest,
                host = normalizedHost,
                share = normalizedShare,
                domain = normalizedDomain,
                username = normalizedUsername,
                password = password
            )
        }
    }
}

/**
 * Collapses saved share-level accounts into one device-level profile per host.
 *
 * Scanning connects to a host without a share so it can list the server's shares.
 * A profile saved by that flow already has a blank share; when only older
 * share-level accounts exist, reuse one of them as the device account.
 */
fun rememberedDeviceProfiles(profiles: List<SmbProfile>): List<SmbProfile> =
    profiles
        .groupBy { it.host.lowercase(Locale.ROOT) }
        .values
        .mapNotNull { hostProfiles ->
            hostProfiles.firstOrNull { it.share.isBlank() }
                ?: hostProfiles.firstOrNull()?.let { profile ->
                    SmbProfile.create(
                        host = profile.host,
                        share = "",
                        domain = profile.domain,
                        username = profile.username,
                        password = profile.password
                    )
                }
        }
        .sortedWith(
            compareBy<SmbProfile> { it.displayName.lowercase(Locale.ROOT) }
                .thenBy { it.host.lowercase(Locale.ROOT) }
        )
