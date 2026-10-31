package com.vr2sbs.bilibili

import java.net.URI

/**
 * A video reference extracted from text copied out of the Bilibili app.
 *
 * Share text usually contains a title and extra description around the link, for example:
 * `[VR180 5.7K] VR视角带逛北大教学楼（关掉B站全景模式可正常播放） https://www.bilibili.com/video/BV1Fd4y1N78r/`
 */
data class BilibiliLink(
    val url: String,
    val bvid: String? = null,
    val avid: Long? = null,
    val page: Int = 1,
    /** True for b23.tv style links that must be resolved over the network first. */
    val shortLink: Boolean = false
)

object BilibiliShareText {
    private val URL_REGEX = Regex(
        "https?://[A-Za-z0-9\\-._~:/?#\\[\\]@!$&'()*+,;=%]+",
        RegexOption.IGNORE_CASE
    )
    private val BVID_REGEX =
        Regex("""(?<![0-9A-Za-z])[Bb][Vv]([1-9A-HJ-NP-Za-km-z]{10})(?![0-9A-Za-z])""")
    private val AVID_REGEX = Regex("""(?<![0-9A-Za-z])[Aa][Vv](\d+)(?![0-9A-Za-z])""")
    private val TRAILING_PUNCTUATION = ".,;:!?，。；：！？、）》】」』”’\"'"

    fun extract(raw: String): BilibiliLink? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) {
            return null
        }
        var foundUrl = false
        for (match in URL_REGEX.findAll(trimmed)) {
            foundUrl = true
            val candidate = match.value.trimEnd { it in TRAILING_PUNCTUATION }
            val link = parseUrl(candidate)
            if (link != null) {
                return link
            }
        }
        // A bare BV/av id is only considered when the text has no web link at all,
        // so an id inside an unrelated URL is never treated as a Bilibili video.
        if (foundUrl) {
            return null
        }
        BVID_REGEX.find(trimmed)?.let { match ->
            val bvid = canonicalBvid(match.value)
            return BilibiliLink(
                url = canonicalPageUrl(bvid = bvid, avid = null, page = 1),
                bvid = bvid
            )
        }
        AVID_REGEX.find(trimmed)?.let { match ->
            val avid = match.groupValues[1].toLongOrNull() ?: return@let
            return BilibiliLink(
                url = canonicalPageUrl(bvid = null, avid = avid, page = 1),
                avid = avid
            )
        }
        return null
    }

    fun parseUrl(rawUrl: String): BilibiliLink? {
        val uri = runCatching { URI(rawUrl) }.getOrNull() ?: return null
        val host = uri.host?.lowercase() ?: return null
        if (!isBilibiliHost(host)) {
            return null
        }

        if (isShortLinkHost(host)) {
            return BilibiliLink(url = uri.toString(), shortLink = true)
        }

        val segments = uri.path
            ?.split('/')
            ?.filter { it.isNotEmpty() }
            .orEmpty()
        val videoIndex = segments.indexOfFirst { it.equals("video", ignoreCase = true) }
        if (videoIndex >= 0 && videoIndex + 1 < segments.size) {
            val idSegment = segments[videoIndex + 1]
            val page = queryParameter(uri.rawQuery, "p")
                ?.toIntOrNull()
                ?.takeIf { it > 0 }
                ?: 1
            BVID_REGEX.matchEntire(idSegment)?.let { match ->
                val bvid = canonicalBvid(match.value)
                return BilibiliLink(
                    url = canonicalPageUrl(bvid = bvid, avid = null, page = page),
                    bvid = bvid,
                    page = page
                )
            }
            AVID_REGEX.matchEntire(idSegment)?.let { match ->
                val avid = match.groupValues[1].toLongOrNull() ?: return@let
                return BilibiliLink(
                    url = canonicalPageUrl(bvid = null, avid = avid, page = page),
                    avid = avid,
                    page = page
                )
            }
        }
        return null
    }

    fun isBilibiliUrl(rawUrl: String): Boolean = parseUrl(rawUrl) != null

    fun canonicalPageUrl(bvid: String?, avid: Long?, page: Int): String {
        val base = when {
            !bvid.isNullOrBlank() -> "https://www.bilibili.com/video/$bvid"
            avid != null && avid > 0L -> "https://www.bilibili.com/video/av$avid"
            else -> "https://www.bilibili.com/"
        }
        return if (page > 1) "$base/?p=$page" else base
    }

    private fun canonicalBvid(raw: String): String = "BV" + raw.drop(2)

    private fun queryParameter(rawQuery: String?, name: String): String? {
        return rawQuery
            ?.split('&')
            ?.firstOrNull { it.substringBefore('=') == name }
            ?.substringAfter('=', missingDelimiterValue = "")
    }

    private fun isBilibiliHost(host: String): Boolean {
        return host == "bilibili.com" ||
            host.endsWith(".bilibili.com") ||
            isShortLinkHost(host)
    }

    private fun isShortLinkHost(host: String): Boolean {
        return host == "b23.tv" ||
            host.endsWith(".b23.tv") ||
            host == "bili2233.cn" ||
            host.endsWith(".bili2233.cn")
    }
}
