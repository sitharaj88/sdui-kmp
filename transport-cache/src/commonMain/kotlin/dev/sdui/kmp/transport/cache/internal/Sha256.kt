package dev.sdui.kmp.transport.cache.internal

/**
 * Tiny pure-Kotlin SHA-256 used to derive safe filenames from arbitrary cache keys.
 *
 * The cache only needs a deterministic, collision-resistant mapping from `String` to a fixed
 * filesystem-legal token; we do not need a vetted, side-channel-hardened implementation. We
 * stay in `commonMain` to keep platform impls simple — no platform-specific crypto bindings.
 */
internal object Sha256 {

    private const val K_HEX_PACKED: String =
        "428a2f98 71374491 b5c0fbcf e9b5dba5 3956c25b 59f111f1 923f82a4 ab1c5ed5 " +
            "d807aa98 12835b01 243185be 550c7dc3 72be5d74 80deb1fe 9bdc06a7 c19bf174 " +
            "e49b69c1 efbe4786 0fc19dc6 240ca1cc 2de92c6f 4a7484aa 5cb0a9dc 76f988da " +
            "983e5152 a831c66d b00327c8 bf597fc7 c6e00bf3 d5a79147 06ca6351 14292967 " +
            "27b70a85 2e1b2138 4d2c6dfc 53380d13 650a7354 766a0abb 81c2c92e 92722c85 " +
            "a2bfe8a1 a81a664b c24b8b70 c76c51a3 d192e819 d6990624 f40e3585 106aa070 " +
            "19a4c116 1e376c08 2748774c 34b0bcb5 391c0cb3 4ed8aa4a 5b9cca4f 682e6ff3 " +
            "748f82ee 78a5636f 84c87814 8cc70208 90befffa a4506ceb bef9a3f7 c67178f2"

    private const val H0_HEX_PACKED: String =
        "6a09e667 bb67ae85 3c6ef372 a54ff53a 510e527f 9b05688c 1f83d9ab 5be0cd19"

    private val K_HEX: List<String> = K_HEX_PACKED.split(' ')
    private val H0_HEX: List<String> = H0_HEX_PACKED.split(' ')

    private val K: IntArray = IntArray(64) { i -> hexToInt(K_HEX[i]) }
    private val H0: IntArray = IntArray(8) { i -> hexToInt(H0_HEX[i]) }

    private fun hexToInt(s: String): Int = s.toLong(radix = 16).toInt()

    fun hexDigest(input: String): String {
        val bytes = input.encodeToByteArray()
        val digest = digest(bytes)
        return buildString(digest.size * 2) {
            for (b in digest) {
                val v = b.toInt() and 0xFF
                append(HEX[v ushr 4])
                append(HEX[v and 0x0F])
            }
        }
    }

    @Suppress("LongMethod", "MagicNumber", "ComplexMethod", "CyclomaticComplexMethod")
    private fun digest(message: ByteArray): ByteArray {
        val padded = pad(message)
        val h = H0.copyOf()
        val w = IntArray(64)
        var off = 0
        while (off < padded.size) {
            for (i in 0 until 16) {
                val j = off + i * 4
                w[i] = ((padded[j].toInt() and 0xFF) shl 24) or
                    ((padded[j + 1].toInt() and 0xFF) shl 16) or
                    ((padded[j + 2].toInt() and 0xFF) shl 8) or
                    (padded[j + 3].toInt() and 0xFF)
            }
            for (i in 16 until 64) {
                val s0 = rotr(w[i - 15], 7) xor rotr(w[i - 15], 18) xor (w[i - 15] ushr 3)
                val s1 = rotr(w[i - 2], 17) xor rotr(w[i - 2], 19) xor (w[i - 2] ushr 10)
                w[i] = w[i - 16] + s0 + w[i - 7] + s1
            }

            var a = h[0]
            var b = h[1]
            var c = h[2]
            var d = h[3]
            var e = h[4]
            var f = h[5]
            var g = h[6]
            var hh = h[7]

            for (i in 0 until 64) {
                val s1 = rotr(e, 6) xor rotr(e, 11) xor rotr(e, 25)
                val ch = (e and f) xor (e.inv() and g)
                val t1 = hh + s1 + ch + K[i] + w[i]
                val s0 = rotr(a, 2) xor rotr(a, 13) xor rotr(a, 22)
                val maj = (a and b) xor (a and c) xor (b and c)
                val t2 = s0 + maj

                hh = g
                g = f
                f = e
                e = d + t1
                d = c
                c = b
                b = a
                a = t1 + t2
            }

            h[0] += a
            h[1] += b
            h[2] += c
            h[3] += d
            h[4] += e
            h[5] += f
            h[6] += g
            h[7] += hh

            off += 64
        }

        val out = ByteArray(32)
        for (i in 0 until 8) {
            out[i * 4] = (h[i] ushr 24).toByte()
            out[i * 4 + 1] = (h[i] ushr 16).toByte()
            out[i * 4 + 2] = (h[i] ushr 8).toByte()
            out[i * 4 + 3] = h[i].toByte()
        }
        return out
    }

    private fun pad(message: ByteArray): ByteArray {
        val bitLen = message.size.toLong() * 8L
        val withOne = message.size + 1
        val padLen = (56 - withOne % 64 + 64) % 64
        val padded = ByteArray(message.size + 1 + padLen + 8)
        message.copyInto(padded)
        padded[message.size] = 0x80.toByte()
        for (i in 0 until 8) {
            padded[padded.size - 8 + i] = (bitLen ushr ((7 - i) * 8)).toByte()
        }
        return padded
    }

    private fun rotr(x: Int, n: Int): Int = (x ushr n) or (x shl (32 - n))

    private val HEX = "0123456789abcdef".toCharArray()
}
