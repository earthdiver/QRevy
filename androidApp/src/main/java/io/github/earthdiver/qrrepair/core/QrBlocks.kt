package io.github.earthdiver.qrrepair.core

import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.decoder.Version

object QrBlockInterleaver {
    /** Mirrors ZXing DataBlock.getDataBlocks(), while carrying bit-level erasure masks and reverse mapping. */
    fun deinterleave(
        extraction: CodewordExtraction,
        versionNumber: Int,
        ecLevel: ErrorCorrectionLevel,
    ): DeinterleavedQr {
        val version = Version.getVersionForNumber(versionNumber)
        require(extraction.codewords.size == version.totalCodewords)
        val ecBlocks = version.getECBlocksForLevel(ecLevel)
        val ecPerBlock = ecBlocks.ecCodewordsPerBlock

        val blocks = buildList {
            for (ecb in ecBlocks.ecBlocks) repeat(ecb.count) {
                val data = ecb.dataCodewords
                add(RsBlockWork(data, IntArray(data + ecPerBlock), IntArray(data + ecPerBlock)))
            }
        }
        require(blocks.isNotEmpty())

        val rawLocations = arrayOfNulls<RawCodewordLocation>(extraction.codewords.size)
        var rawOffset = 0
        fun assign(blockIndex: Int, localIndex: Int) {
            val cw = extraction.codewords[rawOffset]
            val block = blocks[blockIndex]
            block.codewords[localIndex] = cw.value and 0xFF
            block.unknownMasks[localIndex] = cw.unknownBits and 0xFF
            rawLocations[rawOffset] = RawCodewordLocation(blockIndex, localIndex)
            rawOffset++
        }

        val longerBlocksTotalCodewords = blocks.last().codewords.size
        var longerBlocksStartAt = blocks.size - 1
        while (longerBlocksStartAt >= 0 && blocks[longerBlocksStartAt].codewords.size == longerBlocksTotalCodewords) longerBlocksStartAt--
        longerBlocksStartAt++

        val shorterBlocksTotalCodewords = longerBlocksTotalCodewords - if (longerBlocksStartAt == 0) 0 else 1
        val shorterBlocksNumDataCodewords = shorterBlocksTotalCodewords - ecPerBlock

        for (i in 0 until shorterBlocksNumDataCodewords) for (j in blocks.indices) assign(j, i)
        if (longerBlocksStartAt < blocks.size && longerBlocksStartAt > 0) {
            for (j in longerBlocksStartAt until blocks.size) assign(j, shorterBlocksNumDataCodewords)
        }
        val commonBlockTotalCodewords = shorterBlocksTotalCodewords
        for (i in shorterBlocksNumDataCodewords until commonBlockTotalCodewords) for (j in blocks.indices) {
            val localIndex = if (j < longerBlocksStartAt) i else if (longerBlocksStartAt == 0) i else i + 1
            if (localIndex < blocks[j].codewords.size) assign(j, localIndex)
        }
        require(rawOffset == extraction.codewords.size) { "QR deinterleave consumed $rawOffset/${extraction.codewords.size} codewords" }
        return DeinterleavedQr(blocks, rawLocations, ecPerBlock)
    }

    fun reinterleave(deinterleaved: DeinterleavedQr, correctedBlocks: List<IntArray>): IntArray {
        require(correctedBlocks.size == deinterleaved.blocks.size)
        return IntArray(deinterleaved.rawLocations.size) { raw ->
            val loc = requireNotNull(deinterleaved.rawLocations[raw])
            correctedBlocks[loc.blockIndex][loc.localIndex] and 0xFF
        }
    }
}
