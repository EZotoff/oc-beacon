package dev.leonardo.ocbeacon.util

import java.security.SecureRandom

/**
 * RFC 9562 UUIDv7 生成器（unix-ms 前缀 + 随机位）。
 *
 * 用途：Portable Supervisor Seam 4 beacon 回复信封的 `clientMessageID`——
 * 客户端生成、时间有序、用于 supervisor 侧去重（at-least-once 重传时同 ID）。
 * minSdk 26 无平台 UUIDv7，自行实现。
 */
object UuidV7 {

    private val random = SecureRandom()

    fun generate(): String = generateAt(System.currentTimeMillis())

    /** 纯函数核（可测）：48-bit 时间戳 + version 7 + variant 10。 */
    internal fun generateAt(unixMs: Long): String {
        val bytes = ByteArray(16)
        // 48-bit big-endian timestamp
        for (i in 0 until 6) bytes[i] = (unixMs ushr ((5 - i) * 8)).toByte()
        val rand = ByteArray(10)
        random.nextBytes(rand)
        for (i in 0 until 10) bytes[6 + i] = rand[i]
        // version 7（byte 6 高 4 位）
        bytes[6] = ((bytes[6].toInt() and 0x0F) or 0x70).toByte()
        // variant 10（byte 8 高 2 位）
        bytes[8] = ((bytes[8].toInt() and 0x3F) or 0x80).toByte()
        val hex = bytes.joinToString("") { "%02x".format(it) }
        return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-" +
            "${hex.substring(16, 20)}-${hex.substring(20, 32)}"
    }
}
