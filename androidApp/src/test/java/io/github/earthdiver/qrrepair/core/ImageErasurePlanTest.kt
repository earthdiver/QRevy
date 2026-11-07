package io.github.earthdiver.qrrepair.core

import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import org.junit.Assert.*
import org.junit.Test

class ImageErasurePlanTest {
    @Test fun alternatesSinglePairAndTripleMarginsWithoutRepeatingWords() {
        // Eight isolated weak cells must not crowd out words with several weak cells.
        val margins = List(8) { ImageWordMargins(1, 91, 181) } + listOf(
            ImageWordMargins(2, 4, 94), ImageWordMargins(3, 6, 9), ImageWordMargins(4, 8, 12), null,
        )
        val plan = ImageErasurePlan(margins, 4)
        assertEquals(listOf(0, 8, 9, 1, 10, 2, 3, 4), plan.pool)
        assertEquals(98, plan.trialCount)
    }

    @Test fun skipsSelectedWordsWithinEachTurnAndStopsWhenAllAreSelected() {
        for (count in 0..10) {
            val plan = ImageErasurePlan(List(count) { ImageWordMargins(0, 0, 0) }, 4)
            assertEquals((0 until minOf(count, 8)).toList(), plan.pool)
        }
        val margins = listOf(null, ImageWordMargins(254, 508, 762), null)
        assertEquals(listOf(1), ImageErasurePlan(margins, 4).pool)
        assertEquals(listOf(1), ImageErasurePlan(margins, 4).trialSizes)
        assertEquals(1, ImageErasurePlan(margins, 4).trialCount)
        for (room in listOf(0, -1)) {
            val plan = ImageErasurePlan(margins, room)
            assertTrue(plan.pool.isEmpty())
            assertTrue(plan.trialSizes.isEmpty())
            assertEquals(0, plan.trialCount)
        }
        assertTrue(ImageErasurePlan(listOf(null, null), 4).pool.isEmpty())
    }

    @Test fun computesMarginSumsInEachInterleavedBlockUsingOnlyCompleteImageWords() {
        val qr = Encoder.encode("Image evidence in short and long blocks", ErrorCorrectionLevel.Q,
            mapOf(EncodeHintType.QR_VERSION to 5, EncodeHintType.QR_MASK_PATTERN to 3)).matrix
        val matrix = QrMatrix(qr.width)
        for (y in 0 until matrix.dimension) for (x in 0 until matrix.dimension) {
            matrix.set(x, y, if (qr[x, y].toInt() == 1) ModuleValue.ONE else ModuleValue.ZERO, 1f, ModuleSource.IMAGE)
        }
        val words = CodewordExtractor.extract(matrix, 5, 3).codewords
        for ((raw, word) in words.withIndex()) {
            val m = raw % 100
            val values = intArrayOf(254, m + 3, 200, m + 2, 180, m + 1, 160, 140)
            for ((bit, cell) in word.coordinates.withIndex()) matrix.setImageMargin(cell.x, cell.y, values[bit])
        }
        // Every non-image source invalidates the whole word, even if its other margins exist.
        val excludedSources = ModuleSource.entries.filter { it != ModuleSource.IMAGE }
        for ((raw, source) in excludedSources.withIndex()) {
            val cell = words[raw].coordinates.last()
            matrix.set(cell.x, cell.y, matrix[cell.x, cell.y].value, 1f, source)
        }
        val erased = excludedSources.size
        val missing = erased + 1
        for (raw in listOf(erased, missing)) {
            val cell = words[raw].coordinates.last()
            matrix.set(cell.x, cell.y, if (raw == erased) ModuleValue.ERASED else matrix[cell.x, cell.y].value,
                1f, ModuleSource.IMAGE)
        }
        val zero = missing + 1
        for (cell in words[zero].coordinates) matrix.setImageMargin(cell.x, cell.y, 0)
        val extraction = CodewordExtractor.extract(matrix, 5, 3)
        val de = QrBlockInterleaver.deinterleave(extraction, 5, ErrorCorrectionLevel.Q)
        assertEquals(2, de.blocks.map { it.numDataCodewords }.distinct().size)
        val margins = imageWordMargins(de, extraction, matrix)
        for (raw in words.indices) {
            val location = requireNotNull(de.rawLocations[raw])
            val actual = margins[location.blockIndex][location.localIndex]
            val m = raw % 100
            when {
                raw <= missing -> assertNull(actual)
                raw == zero -> assertEquals(ImageWordMargins(0, 0, 0), actual)
                else -> assertEquals(ImageWordMargins(m + 1, 2 * m + 3, 3 * m + 6), actual)
            }
        }
    }
}
