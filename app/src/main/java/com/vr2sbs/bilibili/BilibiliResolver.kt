package com.vr2sbs.bilibili

import com.vr2sbs.source.SourceDescriptor
import com.vr2sbs.source.SourceMediaKind
import com.vr2sbs.source.SourceType

data class BilibiliResolvedSource(
    val source: SourceDescriptor,
    val title: String,
    val qualityLabel: String?,
    val pageUrl: String
)

class BilibiliResolver(
    private val cookieHeaderProvider: () -> String?,
    private val codecSupport: BilibiliCodecSupport = AndroidBilibiliCodecSupport()
) {
    fun resolve(rawText: String): BilibiliResolvedSource {
        val link = BilibiliShareText.extract(rawText)
            ?: throw BilibiliLinkException("No Bilibili video link found")
        val api = BilibiliApiClient(cookieHeaderProvider)
        val videoLink = if (link.shortLink) {
            val finalUrl = api.resolveShortLink(link.url)
            BilibiliShareText.parseUrl(finalUrl)?.takeUnless { it.shortLink }
                ?: throw BilibiliLinkException("Unable to resolve Bilibili short link")
        } else {
            link
        }

        val video = api.fetchVideo(videoLink, page = videoLink.page)
        val streams = api.fetchPlayUrl(video, page = videoLink.page)
        val selection = BilibiliStreamSelector.select(
            streams = streams,
            videoSupported = codecSupport::supportsVideo,
            audioSupported = codecSupport::supportsAudio
        ) ?: throw BilibiliNoStreamException()

        val cookie = cookieHeaderProvider()?.takeIf { it.isNotBlank() }
        val headers = buildMap {
            put("Referer", BilibiliApiClient.BILIBILI_REFERER)
            put("User-Agent", BilibiliApiClient.DEFAULT_USER_AGENT)
            if (cookie != null) {
                put("Cookie", cookie)
            }
        }
        val canonicalUrl = BilibiliShareText.canonicalPageUrl(
            bvid = video.bvid,
            avid = video.avid,
            page = videoLink.page
        )
        return BilibiliResolvedSource(
            source = SourceDescriptor(
                original = videoLink.url,
                normalized = canonicalUrl,
                type = SourceType.HTTP_URL,
                displayName = video.title.takeIf { it.isNotBlank() },
                mediaKind = SourceMediaKind.VIDEO,
                streamUri = selection.video.baseUrl,
                audioStreamUri = selection.audio?.baseUrl,
                httpHeaders = headers
            ),
            title = video.title.takeIf { it.isNotBlank() } ?: video.bvid ?: canonicalUrl,
            qualityLabel = selection.qualityLabel,
            pageUrl = canonicalUrl
        )
    }
}
