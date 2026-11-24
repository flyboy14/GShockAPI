package org.avmedia.gshockapi.io

import org.avmedia.gshockapi.AppNotification
import org.avmedia.gshockapi.NotificationType

// ============================================================================
// Pure Functional Core: Notification Encoding & Decoding
// ============================================================================

/**
 * Pure functional core for notification packet processing.
 *
 * All methods are pure: no mutable state, no side effects.
 * Handles XOR encoding/decoding and length-prefixed string serialization.
 */
object AppNotificationIOFunctional {
    /**
     * Pure decoder: XOR decodes a hex string buffer.
     *
     * Converts hex string to bytes and applies XOR operation with key.
     * No side effects - pure transformation.
     */
    fun xorDecodeBuffer(
        buffer: String,
        key: Int = 0xFF,
    ): ByteArray =
        buffer.chunked(2)
            .map { it.toInt(16).toByte() }
            .map { (it.toInt() xor key).toByte() }
            .toByteArray()

    /**
     * Pure encoder: XOR encodes byte array.
     *
     * Applies XOR operation on all bytes with the given key.
     * No side effects - pure transformation.
     */
    fun xorEncodeBuffer(
        decodedBytes: ByteArray,
        key: Int = 0xFF,
    ): ByteArray =
        decodedBytes
            .map { (it.toInt() xor key).toByte() }
            .toByteArray()

    /**
     * Pure encoder: XOR encodes byte array to hex string.
     *
     * Encodes bytes and converts result to hex string representation.
     * No side effects - pure transformation.
     */
    fun xorEncodeBufferStr(
        decodedBytes: ByteArray,
        key: Int = 0xFF,
    ): String =
        xorEncodeBuffer(decodedBytes, key)
            .joinToString("") { "%02x".format(it) }

    /**
     * Pure parser: Reads length-prefixed string from buffer at offset.
     *
     * Format: [1 byte length][1 byte null][UTF-8 string content]
     * Returns the parsed string and the offset after the string.
     * No side effects - pure parsing.
     */
    fun readLengthPrefixedString(
        buf: ByteArray,
        offset: Int,
    ): Pair<String, Int> {
        require(offset + 2 <= buf.size) { "Not enough data to read length prefix" }
        require(buf[offset + 1] == 0x00.toByte()) { "Expected null second byte in length prefix" }

        val length = buf[offset].toUByte().toInt()

        val start = offset + 2
        val end = start + length
        require(end <= buf.size) { "String length exceeds buffer" }

        val string = buf.copyOfRange(start, end).toString(Charsets.UTF_8)
        return string to end
    }

    /**
     * Pure decoder: Decodes notification packet from byte array.
     *
     * Parses protocol buffer format:
     * - Header (6 bytes): 00 00 00 00 00 01
     * - Type (1 byte), Timestamp (15 bytes), then 4 length-prefixed strings
     * No side effects - pure transformation.
     */
    fun decodeNotificationPacket(buf: ByteArray): AppNotification {
        var offset = 0
        require(buf.size >= 6) { "Buffer too short" }
        offset += 6

        val typeByte = buf[offset++].toInt()
        val notifyType =
            NotificationType.entries.getOrNull(typeByte)
                ?: throw IllegalArgumentException("Invalid NotificationType: $typeByte")

        val timestamp = buf.copyOfRange(offset, offset + 15).toString(Charsets.US_ASCII)
        offset += 15

        val (app, offset1) = readLengthPrefixedString(buf, offset)
        val (title, offset2) = readLengthPrefixedString(buf, offset1)
        val (shortText, offset3) = readLengthPrefixedString(buf, offset2)
        val (text, _) = readLengthPrefixedString(buf, offset3)

        return AppNotification(
            type = notifyType,
            timestamp = timestamp,
            app = app,
            title = title,
            shortText = shortText,
            text = text,
        )
    }

    /**
     * Pure encoder: Encodes string to length-prefixed byte format.
     *
     * Format: [1 byte length][1 byte null][UTF-8 string]
     * Text longer than 255 UTF-8 bytes is cut at a character boundary (long notifications used to
     * throw and never reach the watch).
     * No side effects - pure transformation.
     */
    fun writeLengthPrefixedString(text: String): ByteArray {
        val encoded = truncateUtf8(text, 255).toByteArray(Charsets.UTF_8)
        return byteArrayOf(encoded.size.toByte(), 0x00) + encoded
    }

    /** Longest prefix of [text] whose UTF-8 form fits in [maxBytes], never splitting a code point. Pure. */
    fun truncateUtf8(text: String, maxBytes: Int): String {
        var bytes = 0
        var end = 0
        while (end < text.length) {
            val cp = text.codePointAt(end)
            val size = when {
                cp < 0x80 -> 1
                cp < 0x800 -> 2
                cp < 0x10000 -> 3
                else -> 4
            }
            if (bytes + size > maxBytes) break
            bytes += size
            end += Character.charCount(cp)
        }
        return text.substring(0, end)
    }

    /**
     * Pure encoder: Encodes AppNotification to protocol buffer format.
     *
     * Builds complete notification packet:
     * - Header (6 bytes): 00 00 00 00 00 01
     * - Type (1 byte), Timestamp (15 bytes)
     * - App name, title, short text, and full text as length-prefixed strings
     * The whole packet is kept within [MAX_PACKET] bytes (one BLE write; Android cut longer ones
     * at 247): the strings are shortened in order app, title, short text, text, each getting what
     * the earlier ones left.
     * No side effects - pure transformation.
     */
    fun encodeNotificationPacket(data: AppNotification): ByteArray {
        val head = byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x00, 0x01, data.type.value.toByte()) +
            data.timestamp.toByteArray(Charsets.US_ASCII)
        var left = MAX_PACKET - head.size - 4 * 2 // four length prefixes
        val fields = listOf(data.app, data.title, data.shortText, data.text).map { s ->
            truncateUtf8(s, minOf(255, left.coerceAtLeast(0))).also { left -= it.toByteArray(Charsets.UTF_8).size }
        }
        return fields.fold(head) { acc, s -> acc + writeLengthPrefixedString(s) }
    }

    /** Largest notification packet sent in one write (ATT MTU 250 minus 3 header bytes). */
    const val MAX_PACKET = 247
}

object AppNotificationIO {
    fun xorDecodeBuffer(
        buffer: String,
        key: Int = 0xFF,
    ): ByteArray = AppNotificationIOFunctional.xorDecodeBuffer(buffer, key)

    fun xorEncodeBuffer(
        decodedBytes: ByteArray,
        key: Int = 0xFF,
    ): ByteArray = AppNotificationIOFunctional.xorEncodeBuffer(decodedBytes, key)

    fun xorEncodeBufferStr(
        decodedBytes: ByteArray,
        key: Int = 0xFF,
    ): String = AppNotificationIOFunctional.xorEncodeBufferStr(decodedBytes, key)

    fun decodeNotificationPacket(buf: ByteArray): AppNotification = AppNotificationIOFunctional.decodeNotificationPacket(buf)

    fun encodeNotificationPacket(data: AppNotification): ByteArray = AppNotificationIOFunctional.encodeNotificationPacket(data)
}
