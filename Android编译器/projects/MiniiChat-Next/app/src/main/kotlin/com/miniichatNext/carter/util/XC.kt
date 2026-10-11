package com.miniichatNext.carter.util

import java.security.SecureRandom

object XC {
    private val DEFAULT_KEY = byteArrayOf(
        0x5A, 0x3F, 0x18, 0x7E, 0x4D, 0x2C, 0x6B, 0x09,
        0x71, 0x4A, 0x33, 0x5C, 0x62, 0x1B, 0x28, 0x3E
    )

    fun e(data: ByteArray, customKey: String? = null): ByteArray {
        val key = if (customKey.isNullOrBlank()) DEFAULT_KEY else customKey.toByteArray(Charsets.UTF_8)
        val salt = ByteArray(16)
        SecureRandom().nextBytes(salt)

        val out = ByteArray(16 + data.size)
        System.arraycopy(salt, 0, out, 0, 16)

        for (i in data.indices) {
            val k = key[i % key.size].toInt()
            val s = salt[i % salt.size].toInt()
            val xorKey = (k xor s xor ((i * 31) and 0xFF)) and 0xFF
            out[16 + i] = (data[i].toInt() xor xorKey).toByte()
        }
        return out
    }

    fun d(data: ByteArray, customKey: String? = null): ByteArray {
        if (data.size < 16) throw IllegalArgumentException("Invalid encrypted payload")
        val key = if (customKey.isNullOrBlank()) DEFAULT_KEY else customKey.toByteArray(Charsets.UTF_8)
        val salt = ByteArray(16)
        System.arraycopy(data, 0, salt, 0, 16)

        val plainLen = data.size - 16
        val out = ByteArray(plainLen)

        for (i in 0 until plainLen) {
            val k = key[i % key.size].toInt()
            val s = salt[i % salt.size].toInt()
            val xorKey = (k xor s xor ((i * 31) and 0xFF)) and 0xFF
            out[i] = (data[16 + i].toInt() xor xorKey).toByte()
        }
        return out
    }
}
