package com.vr2sbs.bilibili

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class BilibiliNav(
    val isLoggedIn: Boolean,
    val userName: String?,
    val vipLabel: String?,
    val imgKey: String,
    val subKey: String
)

class BilibiliApiClient(
    private val cookieHeaderProvider: () -> String? = { null },
    private val userAgent: String = DEFAULT_USER_AGENT
) {
    fun fetchNav(): BilibiliNav {
        val root = JSONObject(get(NAV_URL))
        val data = root.optJSONObject("data")
            ?: throw BilibiliApiException(root.optInt("code", -1), messageOf(root))
        val wbi = data.optJSONObject("wbi_img")
            ?: throw BilibiliApiException(root.optInt("code", -1), "Missing WBI keys")
        val imgKey = BilibiliWbi.extractKeyFromUrl(wbi.optString("img_url"))
            ?: throw BilibiliApiException(root.optInt("code", -1), "Missing WBI image key")
        val subKey = BilibiliWbi.extractKeyFromUrl(wbi.optString("sub_url"))
            ?: throw BilibiliApiException(root.optInt("code", -1), "Missing WBI sub key")
        val vipLabel = data.optString("vip_label").takeIf { it.isNotBlank() }
            ?: data.optString("vip_sub_status").takeIf { it.isNotBlank() }
            ?: if (data.optInt("vipStatus") > 0 || data.optInt("vip_status") > 0) "VIP" else null
        return BilibiliNav(
            isLoggedIn = data.optBoolean("isLogin"),
            userName = data.optString("uname").takeIf { it.isNotBlank() },
            vipLabel = vipLabel,
            imgKey = imgKey,
            subKey = subKey
        )
    }

    fun fetchVideo(link: BilibiliLink, page: Int = link.page): BilibiliVideoInfo {
        val query = when {
            !link.bvid.isNullOrBlank() -> "bvid=${BilibiliWbi.encode(link.bvid)}"
            link.avid != null && link.avid > 0L -> "aid=${link.avid}"
            else -> throw BilibiliLinkException("Bilibili link is missing a video id")
        }
        val body = get("$VIEW_URL?$query")
        val info = BilibiliPlayUrlParser.parseVideoInfo(body)
        if (info.cidForPage(page) <= 0L) {
            throw BilibiliApiException(-1, "Bilibili video page ${page} was not found")
        }
        return info
    }

    fun fetchPlayUrl(video: BilibiliVideoInfo, page: Int, qn: Int = MAX_QUALITY): BilibiliDashStreams {
        val cid = video.cidForPage(page)
        if (cid <= 0L) {
            throw BilibiliApiException(-1, "Bilibili video page was not found")
        }
        val bvid = video.bvid
            ?: throw BilibiliApiException(-1, "Bilibili video is missing its BV id")
        val nav = fetchNav()
        val signed = BilibiliWbi.sign(
            params = mapOf(
                "bvid" to bvid,
                "cid" to cid.toString(),
                "qn" to qn.toString(),
                "fnval" to "4048",
                "fnver" to "0",
                "fourk" to "1"
            ),
            imgKey = nav.imgKey,
            subKey = nav.subKey,
            timestampSeconds = System.currentTimeMillis() / 1000L
        )
        return BilibiliPlayUrlParser.parse(get("$PLAYURL_URL?${signed.query}"))
    }

    fun resolveShortLink(shortUrl: String): String {
        val connection = openConnection(shortUrl)
        return try {
            connection.responseCode
            connection.url?.toString() ?: shortUrl
        } catch (error: IOException) {
            throw BilibiliNetworkException(error)
        } finally {
            connection.disconnect()
        }
    }

    private fun get(url: String): String {
        val connection = openConnection(url)
        return try {
            val responseCode = connection.responseCode
            val stream = if (responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (responseCode !in 200..299) {
                throw BilibiliNetworkException(IOException("HTTP $responseCode from Bilibili"))
            }
            body
        } catch (error: IOException) {
            throw BilibiliNetworkException(error)
        } finally {
            connection.disconnect()
        }
    }

    private fun openConnection(url: String): HttpURLConnection {
        val connection = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (error: IOException) {
            throw BilibiliNetworkException(error)
        }
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", userAgent)
        connection.setRequestProperty("Referer", BILIBILI_REFERER)
        connection.setRequestProperty("Accept", "application/json, text/plain, */*")
        cookieHeaderProvider()?.takeIf { it.isNotBlank() }?.let { cookie ->
            connection.setRequestProperty("Cookie", cookie)
        }
        return connection
    }

    private fun messageOf(root: JSONObject): String =
        root.optString("message").ifBlank { "Bilibili API error ${root.optInt("code", -1)}" }

    companion object {
        const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        const val BILIBILI_REFERER = "https://www.bilibili.com/"
        const val MAX_QUALITY = 127

        private const val NAV_URL = "https://api.bilibili.com/x/web-interface/nav"
        private const val VIEW_URL = "https://api.bilibili.com/x/web-interface/view"
        private const val PLAYURL_URL = "https://api.bilibili.com/x/player/wbi/playurl"
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 20_000
    }
}
