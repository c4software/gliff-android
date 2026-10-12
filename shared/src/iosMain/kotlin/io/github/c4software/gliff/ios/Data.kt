package io.github.c4software.gliff.ios

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.Foundation.NSData
import platform.Foundation.NSLock
import platform.Foundation.create
import platform.posix.memcpy

@OptIn(ExperimentalForeignApi::class)
internal fun NSData.toByteArray(): ByteArray {
    val out = ByteArray(length.toInt())
    if (out.isNotEmpty()) out.usePinned { memcpy(it.addressOf(0), bytes, length) }
    return out
}

@OptIn(ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)
internal fun ByteArray.toNSData(length: Int = size): NSData =
    if (length == 0) NSData() else usePinned { NSData.create(bytes = it.addressOf(0), length = length.convert()) }

@OptIn(ExperimentalForeignApi::class)
internal fun sha256(data: ByteArray): ByteArray {
    val digest = UByteArray(CC_SHA256_DIGEST_LENGTH)
    digest.usePinned { out ->
        if (data.isEmpty()) {
            CC_SHA256(null, 0u, out.addressOf(0))
        } else {
            data.usePinned { CC_SHA256(it.addressOf(0), data.size.convert(), out.addressOf(0)) }
        }
    }
    return digest.asByteArray()
}

internal inline fun <T> NSLock.withLock(block: () -> T): T {
    lock()
    try {
        return block()
    } finally {
        unlock()
    }
}
