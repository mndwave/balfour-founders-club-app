package gs.boldthin.loyalty.card

import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import kotlin.math.abs
import kotlin.math.ceil

/**
 * The QR modules exactly as the web card draws them. qrcode.react (nayuki qrcodegen) picks the smallest
 * version for level M, boosts the level as far as it still fits that version (boostEcl defaults to true
 * there), and picks the mask with its own penalty score. zxing differs on the last two (no boost, and a
 * different penalty), so zxing only does the encoding here and both choices follow qrcodegen.
 * QrMatrixTest checks the result against vectors taken from qrcode.react.
 */
class QrMatrix private constructor(val size: Int, private val dark: BooleanArray) {

    fun isDark(x: Int, y: Int): Boolean = dark[y * size + x]

    companion object {
        fun encode(value: String): QrMatrix {
            val version = Encoder.encode(value, ErrorCorrectionLevel.M, mapOf(EncodeHintType.MARGIN to 0)).version.versionNumber
            var level = ErrorCorrectionLevel.M
            for (boost in listOf(ErrorCorrectionLevel.Q, ErrorCorrectionLevel.H)) {
                if (fits(value, boost, version)) level = boost
            }
            var best: QrMatrix? = null
            var bestPenalty = Int.MAX_VALUE
            for (mask in 0 until 8) {
                val m = build(value, level, version, mask)
                val p = m.penalty()
                if (p < bestPenalty) { best = m; bestPenalty = p }
            }
            return best!!
        }

        private fun hints(version: Int, mask: Int? = null) = buildMap<EncodeHintType, Any> {
            put(EncodeHintType.MARGIN, 0)
            put(EncodeHintType.QR_VERSION, version)
            if (mask != null) put(EncodeHintType.QR_MASK_PATTERN, mask)
        }

        private fun fits(value: String, level: ErrorCorrectionLevel, version: Int): Boolean =
            try { Encoder.encode(value, level, hints(version)); true } catch (e: Exception) { false }

        private fun build(value: String, level: ErrorCorrectionLevel, version: Int, mask: Int): QrMatrix {
            val m = Encoder.encode(value, level, hints(version, mask)).matrix
            val n = m.width
            return QrMatrix(n, BooleanArray(n * n) { i -> m.get(i % n, i / n).toInt() == 1 })
        }
    }

    // qrcodegen QrCode.getPenaltyScore(), line for line.
    private fun penalty(): Int {
        var result = 0
        for (line in 0 until size) {
            for (vertical in listOf(false, true)) {
                var runColor = false
                var run = 0
                val history = IntArray(7)
                for (i in 0 until size) {
                    val c = if (vertical) isDark(line, i) else isDark(i, line)
                    if (c == runColor) {
                        run++
                        if (run == 5) result += N1 else if (run > 5) result++
                    } else {
                        addHistory(run, history)
                        if (!runColor) result += countPatterns(history) * N3
                        runColor = c
                        run = 1
                    }
                }
                result += terminateAndCount(runColor, run, history) * N3
            }
        }
        for (y in 0 until size - 1) for (x in 0 until size - 1) {
            val c = isDark(x, y)
            if (c == isDark(x + 1, y) && c == isDark(x, y + 1) && c == isDark(x + 1, y + 1)) result += N2
        }
        val darkCount = dark.count { it }
        val total = size * size
        val k = ceil(abs(darkCount * 20 - total * 10).toDouble() / total).toInt() - 1
        return result + k * N4
    }

    private fun countPatterns(h: IntArray): Int {
        val n = h[1]
        val core = n > 0 && h[2] == n && h[3] == n * 3 && h[4] == n && h[5] == n
        return (if (core && h[0] >= n * 4 && h[6] >= n) 1 else 0) + (if (core && h[6] >= n * 4 && h[0] >= n) 1 else 0)
    }

    private fun terminateAndCount(runColor: Boolean, runLength: Int, h: IntArray): Int {
        var len = runLength
        if (runColor) { addHistory(len, h); len = 0 }
        len += size
        addHistory(len, h)
        return countPatterns(h)
    }

    private fun addHistory(runLength: Int, h: IntArray) {
        val len = if (h[0] == 0) runLength + size else runLength
        System.arraycopy(h, 0, h, 1, 6)
        h[0] = len
    }
}

private const val N1 = 3
private const val N2 = 3
private const val N3 = 40
private const val N4 = 10
