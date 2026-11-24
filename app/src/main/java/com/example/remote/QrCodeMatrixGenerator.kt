package com.example.remote

/**
 * Compact pure-Kotlin ISO/IEC 18004 QR Code generator (Byte mode, ECC Level L, Versions 1..6).
 * Supports URLs up to 134 UTF-8 bytes (e.g. "http://192.168.1.42:8765/?pin=482910") with zero
 * external library bloat so CineStream's universal APK stays lean.
 */
object QrCodeMatrixGenerator {

    private data class VersionSpec(
        val version: Int,
        val size: Int,
        val dataCodewords: Int,
        val ecCodewordsPerBlock: Int,
        val numBlocks: Int,
        val alignmentCenters: IntArray
    )

    // Level L specifications for Versions 1..6 (single-group block layouts)
    private val VERSION_SPECS = listOf(
        VersionSpec(1, 21, 19, 7, 1, intArrayOf()),
        VersionSpec(2, 25, 34, 10, 1, intArrayOf(6, 18)),
        VersionSpec(3, 29, 55, 15, 1, intArrayOf(6, 22)),
        VersionSpec(4, 33, 80, 20, 1, intArrayOf(6, 26)),
        VersionSpec(5, 37, 108, 26, 1, intArrayOf(6, 30)),
        VersionSpec(6, 41, 136, 18, 2, intArrayOf(6, 34))
    )

    private val GF_EXP = IntArray(512)
    private val GF_LOG = IntArray(256)

    init {
        var x = 1
        for (i in 0 until 255) {
            GF_EXP[i] = x
            GF_LOG[x] = i
            x = x shl 1
            if (x and 0x100 != 0) x = x xor 0x11D
        }
        for (i in 255 until 512) {
            GF_EXP[i] = GF_EXP[i - 255]
        }
    }

    /**
     * Generates a square boolean matrix where `true` = dark module and `false` = light module.
     */
    fun generate(text: String): Array<BooleanArray> {
        val rawBytes = text.toByteArray(Charsets.UTF_8)
        // Byte mode overhead: 4 bits mode + 8 bits length + up to 4 bits terminator = 2 bytes
        val spec = VERSION_SPECS.firstOrNull { rawBytes.size + 2 <= it.dataCodewords }
            ?: VERSION_SPECS.last()
        val payload = if (rawBytes.size + 2 > spec.dataCodewords) {
            rawBytes.copyOf(spec.dataCodewords - 2)
        } else {
            rawBytes
        }

        val dataCodewords = encodeDataCodewords(payload, spec.dataCodewords)
        val finalCodewords = interleaveWithEcc(dataCodewords, spec)
        val size = spec.size
        val modules = Array(size) { BooleanArray(size) }
        val isFunction = Array(size) { BooleanArray(size) }

        placeFunctionPatterns(modules, isFunction, spec)
        placeDataBits(modules, isFunction, finalCodewords, size)

        // Evaluate all 8 masks and pick the lowest penalty mask per ISO/IEC 18004
        var bestMask = 0
        var bestPenalty = Int.MAX_VALUE
        var bestMatrix = modules
        for (mask in 0..7) {
            val candidate = Array(size) { r -> modules[r].clone() }
            applyMask(candidate, isFunction, mask, size)
            placeFormatInfo(candidate, mask, size)
            val penalty = computePenalty(candidate, size)
            if (penalty < bestPenalty) {
                bestPenalty = penalty
                bestMask = mask
                bestMatrix = candidate
            }
        }
        placeFormatInfo(bestMatrix, bestMask, size)
        return bestMatrix
    }

    private fun encodeDataCodewords(payload: ByteArray, capacity: Int): IntArray {
        val bits = ArrayList<Int>(capacity * 8)
        fun appendBits(value: Int, length: Int) {
            for (i in (length - 1) downTo 0) {
                bits.add((value ushr i) and 1)
            }
        }
        // Byte mode indicator (0100) + 8-bit character count (versions 1..9)
        appendBits(0b0100, 4)
        appendBits(payload.size, 8)
        for (b in payload) {
            appendBits(b.toInt() and 0xFF, 8)
        }
        val maxBits = capacity * 8
        val terminatorLen = minOf(4, maxBits - bits.size)
        repeat(terminatorLen) { bits.add(0) }
        while (bits.size % 8 != 0) bits.add(0)

        val codewords = IntArray(capacity)
        var idx = 0
        var bitPos = 0
        while (bitPos + 7 < bits.size && idx < capacity) {
            var byteVal = 0
            for (k in 0 until 8) {
                byteVal = (byteVal shl 1) or bits[bitPos + k]
            }
            codewords[idx++] = byteVal
            bitPos += 8
        }
        var padToggle = true
        while (idx < capacity) {
            codewords[idx++] = if (padToggle) 0xEC else 0x11
            padToggle = !padToggle
        }
        return codewords
    }

    private fun interleaveWithEcc(data: IntArray, spec: VersionSpec): IntArray {
        val blockLen = data.size / spec.numBlocks
        val dataBlocks = Array(spec.numBlocks) { b ->
            data.copyOfRange(b * blockLen, (b + 1) * blockLen)
        }
        val eccBlocks = Array(spec.numBlocks) { b ->
            computeReedSolomonEcc(dataBlocks[b], spec.ecCodewordsPerBlock)
        }
        val out = IntArray(data.size + spec.numBlocks * spec.ecCodewordsPerBlock)
        var pos = 0
        for (i in 0 until blockLen) {
            for (b in 0 until spec.numBlocks) {
                out[pos++] = dataBlocks[b][i]
            }
        }
        for (i in 0 until spec.ecCodewordsPerBlock) {
            for (b in 0 until spec.numBlocks) {
                out[pos++] = eccBlocks[b][i]
            }
        }
        return out
    }

    private fun gfMul(a: Int, b: Int): Int {
        if (a == 0 || b == 0) return 0
        return GF_EXP[GF_LOG[a] + GF_LOG[b]]
    }

    private fun computeReedSolomonEcc(data: IntArray, ecCount: Int): IntArray {
        var gen = intArrayOf(1)
        for (i in 0 until ecCount) {
            val next = IntArray(gen.size + 1)
            val factor = GF_EXP[i]
            for (j in gen.indices) {
                next[j] = next[j] xor gen[j]
                next[j + 1] = next[j + 1] xor gfMul(gen[j], factor)
            }
            gen = next
        }
        val rem = IntArray(ecCount)
        for (d in data) {
            val factor = d xor rem[0]
            System.arraycopy(rem, 1, rem, 0, ecCount - 1)
            rem[ecCount - 1] = 0
            if (factor != 0) {
                for (j in 0 until ecCount) {
                    rem[j] = rem[j] xor gfMul(gen[j + 1], factor)
                }
            }
        }
        return rem
    }

    private fun placeFunctionPatterns(
        modules: Array<BooleanArray>,
        isFunction: Array<BooleanArray>,
        spec: VersionSpec
    ) {
        val size = spec.size
        fun setFunc(r: Int, c: Int, dark: Boolean) {
            if (r in 0 until size && c in 0 until size) {
                modules[r][c] = dark
                isFunction[r][c] = true
            }
        }

        // Finder patterns + 1-module white separators
        val finderOrigins = arrayOf(0 to 0, 0 to (size - 7), (size - 7) to 0)
        for ((fr, fc) in finderOrigins) {
            for (dr in -1..7) {
                for (dc in -1..7) {
                    val r = fr + dr
                    val c = fc + dc
                    if (r in 0 until size && c in 0 until size) {
                        val inOuter = dr in 0..6 && dc in 0..6
                        val dark = inOuter && (
                            dr == 0 || dr == 6 || dc == 0 || dc == 6 ||
                                (dr in 2..4 && dc in 2..4)
                            )
                        setFunc(r, c, dark)
                    }
                }
            }
        }

        // Alignment patterns (versions >= 2)
        val centers = spec.alignmentCenters
        for (cr in centers) {
            for (cc in centers) {
                val overlapsFinder = (cr == 6 && cc == 6) ||
                    (cr == 6 && cc == size - 7) ||
                    (cr == size - 7 && cc == 6)
                if (overlapsFinder) continue
                for (dr in -2..2) {
                    for (dc in -2..2) {
                        val dist = maxOf(kotlin.math.abs(dr), kotlin.math.abs(dc))
                        setFunc(cr + dr, cc + dc, dist != 1)
                    }
                }
            }
        }

        // Timing patterns
        for (i in 8 until size - 8) {
            val dark = (i % 2 == 0)
            if (!isFunction[6][i]) setFunc(6, i, dark)
            if (!isFunction[i][6]) setFunc(i, 6, dark)
        }

        // Reserve format info strips + mandatory dark module
        for (i in 0..8) {
            if (i != 6) {
                isFunction[8][i] = true
                isFunction[i][8] = true
            }
        }
        for (i in 0..7) {
            isFunction[8][size - 1 - i] = true
            isFunction[size - 1 - i][8] = true
        }
        setFunc(size - 8, 8, true)
    }

    private fun placeDataBits(
        modules: Array<BooleanArray>,
        isFunction: Array<BooleanArray>,
        codewords: IntArray,
        size: Int
    ) {
        val totalBits = codewords.size * 8
        var bitIdx = 0
        var upward = true
        var col = size - 1
        while (col > 0) {
            if (col == 6) col-- // Skip vertical timing column
            for (step in 0 until size) {
                val row = if (upward) size - 1 - step else step
                for (dx in 0..1) {
                    val c = col - dx
                    if (!isFunction[row][c]) {
                        val dark = if (bitIdx < totalBits) {
                            val cw = codewords[bitIdx ushr 3]
                            ((cw ushr (7 - (bitIdx and 7))) and 1) != 0
                        } else {
                            false
                        }
                        modules[row][c] = dark
                        bitIdx++
                    }
                }
            }
            upward = !upward
            col -= 2
        }
    }

    private fun applyMask(
        modules: Array<BooleanArray>,
        isFunction: Array<BooleanArray>,
        mask: Int,
        size: Int
    ) {
        for (r in 0 until size) {
            for (c in 0 until size) {
                if (isFunction[r][c]) continue
                val invert = when (mask) {
                    0 -> (r + c) % 2 == 0
                    1 -> r % 2 == 0
                    2 -> c % 3 == 0
                    3 -> (r + c) % 3 == 0
                    4 -> ((r / 2) + (c / 3)) % 2 == 0
                    5 -> ((r * c) % 2) + ((r * c) % 3) == 0
                    6 -> (((r * c) % 2) + ((r * c) % 3)) % 2 == 0
                    else -> (((r + c) % 2) + ((r * c) % 3)) % 2 == 0
                }
                if (invert) modules[r][c] = !modules[r][c]
            }
        }
    }

    private fun placeFormatInfo(modules: Array<BooleanArray>, mask: Int, size: Int) {
        // Error correction level L = 01 (2 bits) + 3-bit mask -> 5-bit data
        val data = (0b01 shl 3) or (mask and 0b111)
        var rem = data
        repeat(10) {
            rem = (rem shl 1) xor (if ((rem ushr 9) != 0) 0x537 else 0)
        }
        val bits = ((data shl 10) or (rem and 0x3FF)) xor 0x5412

        fun bit(i: Int): Boolean = ((bits ushr i) and 1) != 0

        for (i in 0..5) modules[8][i] = bit(14 - i)
        modules[8][7] = bit(8)
        modules[8][8] = bit(7)
        modules[7][8] = bit(6)
        for (i in 0..5) modules[5 - i][8] = bit(5 - i)

        for (i in 0..7) modules[8][size - 1 - i] = bit(i)
        for (i in 0..6) modules[size - 7 + i][8] = bit(14 - i)
        modules[size - 8][8] = true
    }

    private fun computePenalty(modules: Array<BooleanArray>, size: Int): Int {
        var penalty = 0
        // Rule 1: adjacent runs >= 5
        for (r in 0 until size) {
            var runLen = 1
            for (c in 1 until size) {
                if (modules[r][c] == modules[r][c - 1]) {
                    runLen++
                    if (runLen == 5) penalty += 3 else if (runLen > 5) penalty += 1
                } else {
                    runLen = 1
                }
            }
        }
        for (c in 0 until size) {
            var runLen = 1
            for (r in 1 until size) {
                if (modules[r][c] == modules[r - 1][c]) {
                    runLen++
                    if (runLen == 5) penalty += 3 else if (runLen > 5) penalty += 1
                } else {
                    runLen = 1
                }
            }
        }
        // Rule 2: 2x2 blocks
        for (r in 0 until size - 1) {
            for (c in 0 until size - 1) {
                val color = modules[r][c]
                if (color == modules[r][c + 1] && color == modules[r + 1][c] && color == modules[r + 1][c + 1]) {
                    penalty += 3
                }
            }
        }
        return penalty
    }
}
