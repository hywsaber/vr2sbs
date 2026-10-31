package com.vr2sbs.bilibili

import java.security.MessageDigest

data class BilibiliSignedQuery(
    val query: String,
    val wts: Long,
    val wRid: String
)

/**
 * Bilibili's WBI request signing. See the reference algorithm:
 * https://github.com/SocialSisterYi/bilibili-API-collect/blob/master/docs/misc/sign/wbi.md
 */
object BilibiliWbi {
    private val MIXIN_KEY_ENC_TAB = intArrayOf(
        46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35,
        27, 43, 5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13,
        37, 48, 7, 16, 24, 55, 40, 61, 26, 17, 0, 1, 60, 51, 30, 4,
        22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36, 20, 34, 44, 52
    )
    private val FILTERED_CHARACTERS = setOf('!', '\'', '(', ')', '*')
    private const val HEX = "0123456789ABCDEF"

    fun mixinKey(imgKey: String, subKey: String): String {
        val raw = imgKey + subKey
        val result = StringBuilder(32)
        for (index in MIXIN_KEY_ENC_TAB) {
            if (index >= raw.length) {
                continue
            }
            result.append(raw[index])
            if (result.length == 32) {
                break
            }
        }
        return result.toString()
    }

    fun sign(
        params: Map<String, String>,
        imgKey: String,
        subKey: String,
        timestampSeconds: Long
    ): BilibiliSignedQuery {
        val signedParams = params.toMutableMap()
        signedParams["wts"] = timestampSeconds.toString()
        val query = signedParams.toSortedMap().entries.joinToString("&") { (key, value) ->
            val filtered = value.filterNot { it in FILTERED_CHARACTERS }
            "${encode(key)}=${encode(filtered)}"
        }
        val wRid = md5(query + mixinKey(imgKey, subKey))
        return BilibiliSignedQuery(
            query = "$query&w_rid=$wRid",
            wts = timestampSeconds,
            wRid = wRid
        )
    }

    /** Percent-encodes like JavaScript's encodeURIComponent, with uppercase hex digits. */
    internal fun encode(value: String): String {
        val bytes = value.toByteArray(Charsets.UTF_8)
        val result = StringBuilder(bytes.size)
        for (byte in bytes) {
            val code = byte.toInt() and 0xFF
            val character = code.toChar()
            val unreserved = character in 'A'..'Z' ||
                character in 'a'..'z' ||
                character in '0'..'9' ||
                character == '-' || character == '_' || character == '.' || character == '~'
            if (unreserved) {
                result.append(character)
            } else {
                result.append('%')
                result.append(HEX[code shr 4])
                result.append(HEX[code and 0x0F])
            }
        }
        return result.toString()
    }

    internal fun md5(value: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { byte ->
            val code = byte.toInt() and 0xFF
            "${HEX[code shr 4]}${HEX[code and 0x0F]}"
        }.lowercase()
    }

    fun extractKeyFromUrl(url: String): String? {
        val fileName = url.substringAfterLast('/', missingDelimiterValue = "")
            .substringBefore('.')
        return fileName.takeIf { it.isNotBlank() }
    }
}
