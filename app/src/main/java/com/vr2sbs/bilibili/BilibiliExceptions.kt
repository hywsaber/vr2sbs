package com.vr2sbs.bilibili

open class BilibiliException(message: String, cause: Throwable? = null) : Exception(message, cause)

class BilibiliApiException(
    val code: Int,
    message: String
) : BilibiliException(message)

class BilibiliNetworkException(cause: Throwable) :
    BilibiliException(cause.message ?: "Unable to reach Bilibili", cause)

class BilibiliLinkException(message: String) : BilibiliException(message)

class BilibiliNoStreamException : BilibiliException("No playable Bilibili stream was found")
