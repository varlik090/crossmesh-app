package net.cypher.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sqrt

object Stego {
    private val magic = byteArrayOf('C'.code.toByte(), 'N'.code.toByte(), 'P'.code.toByte(), '2'.code.toByte())
    private const val HEADER_SIZE = 8
    private const val MIN_SIDE = 128
    private const val MAX_PAYLOAD = 128 * 1024

    fun embed(data: ByteArray): ByteArray {
        require(data.size <= MAX_PAYLOAD)
        val payload = ByteArray(HEADER_SIZE + data.size)
        System.arraycopy(magic, 0, payload, 0, 4)
        val len = data.size
        payload[4] = ((len ushr 24) and 0xff).toByte()
        payload[5] = ((len ushr 16) and 0xff).toByte()
        payload[6] = ((len ushr 8) and 0xff).toByte()
        payload[7] = (len and 0xff).toByte()
        System.arraycopy(data, 0, payload, HEADER_SIZE, data.size)

        val bitCount = payload.size * 8
        val neededPixels = (bitCount + 2) / 3
        val side = max(MIN_SIDE, ceil(sqrt(neededPixels.toDouble())).toInt())
        val rng = SecureRandom()
        val pixels = IntArray(side * side)
        var bitIndex = 0

        fun nextBit(): Int {
            if (bitIndex >= bitCount) return 0
            val byteIndex = bitIndex / 8
            val shift = 7 - (bitIndex % 8)
            bitIndex++
            return (payload[byteIndex].toInt() ushr shift) and 1
        }

        for (i in pixels.indices) {
            val rgb = ByteArray(3).also { rng.nextBytes(it) }
            var r = rgb[0].toInt() and 0xff
            var g = rgb[1].toInt() and 0xff
            var b = rgb[2].toInt() and 0xff
            if (bitIndex < bitCount) r = (r and 0xfe) or nextBit()
            if (bitIndex < bitCount) g = (g and 0xfe) or nextBit()
            if (bitIndex < bitCount) b = (b and 0xfe) or nextBit()
            pixels[i] = (0xff shl 24) or (r shl 16) or (g shl 8) or b
        }

        val bmp = Bitmap.createBitmap(pixels, side, side, Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream().use { out ->
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            bmp.recycle()
            out.toByteArray()
        }
    }

    fun extract(png: ByteArray): ByteArray {
        val bmp = BitmapFactory.decodeByteArray(png, 0, png.size)
            ?: throw IllegalArgumentException("Invalid PNG")
        val pixels = IntArray(bmp.width * bmp.height)
        bmp.getPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        bmp.recycle()

        fun collectBytes(count: Int): ByteArray {
            val out = ByteArray(count)
            var bitIndex = 0
            for (px in pixels) {
                val channels = intArrayOf((px ushr 16) and 0xff, (px ushr 8) and 0xff, px and 0xff)
                for (ch in channels) {
                    if (bitIndex >= count * 8) return out
                    val byteIndex = bitIndex / 8
                    out[byteIndex] = ((out[byteIndex].toInt() shl 1) or (ch and 1)).toByte()
                    if (bitIndex % 8 == 7) {
                        // already complete; shifts are accumulated MSB first
                    }
                    bitIndex++
                }
            }
            if (bitIndex < count * 8) throw IllegalArgumentException("PNG payload too short")
            return out
        }

        // Decode through a bit cursor to avoid byte-boundary ambiguity.
        val bits = ArrayList<Int>(pixels.size * 3)
        for (px in pixels) {
            bits.add(((px ushr 16) and 0xff) and 1)
            bits.add(((px ushr 8) and 0xff) and 1)
            bits.add((px and 0xff) and 1)
        }
        fun bytesFrom(startBit: Int, count: Int): ByteArray {
            val out = ByteArray(count)
            var p = startBit
            for (i in 0 until count) {
                var v = 0
                repeat(8) { v = (v shl 1) or bits[p++] }
                out[i] = v.toByte()
            }
            return out
        }

        val header = bytesFrom(0, HEADER_SIZE)
        if (!header.copyOfRange(0, 4).contentEquals(magic)) throw IllegalArgumentException("Invalid CNP2")
        val len = ((header[4].toInt() and 0xff) shl 24) or
            ((header[5].toInt() and 0xff) shl 16) or
            ((header[6].toInt() and 0xff) shl 8) or
            (header[7].toInt() and 0xff)
        require(len in 0..MAX_PAYLOAD)
        require((HEADER_SIZE + len) * 8 <= bits.size)
        return bytesFrom(HEADER_SIZE * 8, len)
    }
}
