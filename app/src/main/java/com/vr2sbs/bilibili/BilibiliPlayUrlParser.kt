package com.vr2sbs.bilibili

import org.json.JSONArray
import org.json.JSONObject

internal object BilibiliPlayUrlParser {
    fun parse(body: String): BilibiliDashStreams {
        val root = JSONObject(body)
        ensureSuccess(root)
        val data = root.optJSONObject("data")
            ?: throw BilibiliApiException(root.optInt("code", -1), "Missing playurl data")

        val dash = data.optJSONObject("dash")
        if (dash != null) {
            return BilibiliDashStreams(
                videos = parseVideos(dash.optJSONArray("video")),
                audios = parseAudios(dash.optJSONArray("audio")),
                dolbyAudios = parseAudios(dash.optJSONObject("dolby")?.optJSONArray("audio")),
                flacAudios = parseFlacAudios(dash)
            )
        }

        // Older/simple responses use a single progressive URL instead of DASH.
        val durl = data.optJSONArray("durl")
        if (durl != null && durl.length() > 0) {
            val url = durl.optJSONObject(0)?.optString("url").orEmpty()
            if (url.isNotBlank()) {
                return BilibiliDashStreams(
                    videos = listOf(
                        BilibiliVideoStream(
                            qualityId = data.optInt("quality"),
                            baseUrl = url,
                            bandwidth = 0L,
                            codecs = "",
                            width = 0,
                            height = 0
                        )
                    ),
                    audios = emptyList()
                )
            }
        }
        throw BilibiliApiException(root.optInt("code", -1), "No playable streams in playurl response")
    }

    fun parseVideoInfo(body: String): BilibiliVideoInfo {
        val root = JSONObject(body)
        ensureSuccess(root)
        val data = root.optJSONObject("data")
            ?: throw BilibiliApiException(root.optInt("code", -1), "Missing video data")
        val bvid = data.optString("bvid").takeIf { it.isNotBlank() }
        val aid = data.optLong("aid")
        if (bvid == null && aid <= 0L) {
            throw BilibiliApiException(root.optInt("code", -1), "Missing video id")
        }
        return BilibiliVideoInfo(
            bvid = bvid,
            avid = aid,
            title = data.optString("title"),
            durationSeconds = data.optLong("duration"),
            pages = parsePages(data.optJSONArray("pages")),
            defaultCid = data.optLong("cid")
        )
    }

    private fun ensureSuccess(root: JSONObject) {
        val code = root.optInt("code", -1)
        if (code != 0) {
            throw BilibiliApiException(
                code = code,
                message = root.optString("message").ifBlank { "Bilibili API error $code" }
            )
        }
    }

    private fun parsePages(array: JSONArray?): List<BilibiliVideoPage> {
        if (array == null) {
            return emptyList()
        }
        return (0 until array.length()).mapNotNull { index ->
            val page = array.optJSONObject(index) ?: return@mapNotNull null
            val cid = page.optLong("cid")
            if (cid <= 0L) {
                return@mapNotNull null
            }
            BilibiliVideoPage(
                page = page.optInt("page", index + 1),
                cid = cid,
                title = page.optString("part")
            )
        }
    }

    private fun parseVideos(array: JSONArray?): List<BilibiliVideoStream> {
        if (array == null) {
            return emptyList()
        }
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val url = item.stringOrNull("baseUrl") ?: item.stringOrNull("base_url")
            if (url.isNullOrBlank()) {
                return@mapNotNull null
            }
            BilibiliVideoStream(
                qualityId = item.optInt("id"),
                baseUrl = url,
                bandwidth = item.optLong("bandwidth"),
                codecs = item.optString("codecs"),
                width = item.optInt("width"),
                height = item.optInt("height")
            )
        }
    }

    private fun parseAudios(array: JSONArray?): List<BilibiliAudioStream> {
        if (array == null) {
            return emptyList()
        }
        return (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.let(::parseAudio)
        }
    }

    private fun parseAudio(item: JSONObject): BilibiliAudioStream? {
        val url = item.stringOrNull("baseUrl") ?: item.stringOrNull("base_url")
        if (url.isNullOrBlank()) {
            return null
        }
        return BilibiliAudioStream(
            id = item.optInt("id"),
            baseUrl = url,
            bandwidth = item.optLong("bandwidth"),
            codecs = item.optString("codecs")
        )
    }

    private fun parseFlacAudios(dash: JSONObject): List<BilibiliAudioStream> {
        val flac = dash.optJSONObject("flac") ?: return emptyList()
        val audio = flac.optJSONObject("audio") ?: return emptyList()
        return listOfNotNull(parseAudio(audio))
    }

    private fun JSONObject.stringOrNull(key: String): String? {
        if (!has(key) || isNull(key)) {
            return null
        }
        return optString(key).takeIf { it.isNotBlank() }
    }
}

data class BilibiliVideoPage(
    val page: Int,
    val cid: Long,
    val title: String
)

data class BilibiliVideoInfo(
    val bvid: String?,
    val avid: Long,
    val title: String,
    val durationSeconds: Long,
    val pages: List<BilibiliVideoPage> = emptyList(),
    val defaultCid: Long = 0L
) {
    fun cidForPage(page: Int): Long {
        if (pages.isEmpty()) {
            return if (page <= 1) defaultCid else 0L
        }
        return pages.firstOrNull { it.page == page }?.cid ?: 0L
    }
}
