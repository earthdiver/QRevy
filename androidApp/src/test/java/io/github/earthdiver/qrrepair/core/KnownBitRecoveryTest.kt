package io.github.earthdiver.qrrepair.core

import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import org.junit.Assert.*
import org.junit.Test

class KnownBitRecoveryTest {
    private fun fixture(mask: Int, header: Int? = null): QrMatrix {
        val qr = Encoder.encode("abcdefghijklm", ErrorCorrectionLevel.L,
            mapOf(EncodeHintType.QR_VERSION to 1, EncodeHintType.QR_MASK_PATTERN to mask))
        val m = QrMatrix(21).apply {
            for (y in 0 until 21) for (x in 0 until 21) set(x,y,
                if (qr.matrix.get(x,y).toInt() == 1) ModuleValue.ONE else ModuleValue.ZERO,1f,ModuleSource.IMAGE)
        }
        val words = CodewordExtractor.extract(m,1,mask).codewords
        for (i in listOf(5,6,19,20,21)) for (c in words[i].coordinates) m.set(c.x,c.y,ModuleValue.ERASED,0f,ModuleSource.IMAGE)
        for ((i,bit) in listOf(if (header == null) 22 to 0 else header / 8 to header % 8, 23 to 1)) {
            val c = words[i].coordinates[bit]
            m.set(c.x,c.y,if(m[c.x,c.y].value == ModuleValue.ONE) ModuleValue.ZERO else ModuleValue.ONE,1f,ModuleSource.IMAGE)
        }
        return m
    }
    private fun recover(m: QrMatrix, mask: Int, limit: Int = 2, trust: Boolean = false,
        budget: KnownBitErrorBudget = KnownBitErrorBudget()) = RecoveryEngine().recoverWithDiagnostics(m,
        formatConstraint = QrFormatConstraint(ErrorCorrectionLevel.L,mask), maxKnownDataEccMismatches = 2,
        knownBitErrorLimit = limit, knownBitErrorBudget = budget, trustKnownPayloadCount = trust)

    @Test fun repairsMixedFullByteErasuresAndTwoUnknownErrorPositions() {
        for(mask in 0..2) {
            val m = fixture(mask)
            assertFalse(recover(m,mask,0).candidates.any { it.text == "abcdefghijklm" })
            val result = recover(m,mask).candidates.single { it.text == "abcdefghijklm" }
            assertEquals(2,result.knownBitMismatchesAfterCorrection)
            assertTrue(result.actualBlockMethods.any { it.knownBitErrors?.size == 2 })
            assertEquals(40,m.erasureCount())
            val traces=RepairTraceBuilder.build(m,RepairTraceBuilder.candidateMatrix(m,result,RecoveryEngine()),result)
            assertEquals(2,traces.values.count { it.knownBitError })
        }
    }
    @Test fun recomputesGrammarAfterModeAndCountErrors() {
        for(bit in listOf(1,10)) {
            val m = fixture(0,bit)
            assertTrue(recover(m,0).candidates.any { it.text == "abcdefghijklm" })
            assertFalse(recover(m,0,trust=true).candidates.any { it.text == "abcdefghijklm" })
        }
    }
    @Test fun fixedHeaderCannotBeFlippedAndZeroBudgetReportsIncomplete() {
        val m = fixture(0,1)
        val c = CodewordExtractor.extract(m,1,0).codewords[0].coordinates[1]
        m[c.x,c.y].source = ModuleSource.USER
        assertFalse(recover(m,0).candidates.any { it.text == "abcdefghijklm" })
        val stopped = recover(fixture(0),0,budget=KnownBitErrorBudget(maxTrials=0))
        assertTrue(SearchIncompleteReason.KNOWN_BIT_SEARCH_LIMIT in stopped.searchDiagnostic.incompleteReasons)
        val timed = recover(fixture(0),0,budget=KnownBitErrorBudget(maxMillis=0))
        assertTrue(SearchIncompleteReason.KNOWN_BIT_SEARCH_TIME_LIMIT in timed.searchDiagnostic.incompleteReasons)
    }
    @Test fun distributesTwoErrorsAcrossBlocksAndHonorsWholeQrLimit() {
        val qr = Encoder.encode("abcdefghijklm", ErrorCorrectionLevel.Q,
            mapOf(EncodeHintType.QR_VERSION to 3, EncodeHintType.QR_MASK_PATTERN to 0))
        val m = QrMatrix(29).apply {
            for (y in 0 until 29) for (x in 0 until 29) set(x,y,
                if(qr.matrix.get(x,y).toInt()==1) ModuleValue.ONE else ModuleValue.ZERO,1f,ModuleSource.IMAGE)
        }
        val ex = CodewordExtractor.extract(m,3,0)
        val de = QrBlockInterleaver.deinterleave(ex,3,ErrorCorrectionLevel.Q)
        for (block in de.blocks.indices) {
            val raws = de.rawLocations.indices.filter { de.rawLocations[it]!!.blockIndex == block }
            val erased = listOf(raws[5]) + raws.takeLast(16)
            erased.forEach { raw -> ex.codewords[raw].coordinates.forEach { c -> m.set(c.x,c.y,ModuleValue.ERASED,0f,ModuleSource.IMAGE) } }
            val c=ex.codewords[raws[6]].coordinates[0]
            m.set(c.x,c.y,if(m[c.x,c.y].value==ModuleValue.ONE)ModuleValue.ZERO else ModuleValue.ONE,1f,ModuleSource.IMAGE)
        }
        val observed = QrBlockInterleaver.deinterleave(CodewordExtractor.extract(m,3,0),3,ErrorCorrectionLevel.Q)
        fun search(limit:Int):List<List<PartialCodewordSolver.Result>> {
            val results=mutableListOf<List<PartialCodewordSolver.Result>>()
            KnownBitQrSearch.run(observed,3,limit,observed.blocks.map { emptySet() },12,32L*1024*1024,
                KnownBitErrorBudget(),RecoveryCancellation.NONE) { results+=it }
            return results
        }
        assertTrue(search(1).isEmpty())
        val candidates=search(2)
        assertTrue(candidates.isNotEmpty())
        assertTrue(candidates.all { c -> c.sumOf { it.knownBitErrors.size } == 2 })
    }

    @Test fun priorityExampleCanUseKnownBitFallbackWithoutHeaderTrustOrPriorityMark() {
        val path = java.io.File("../docs/examples/priority-list-decoding/before.txt")
        val m=QrTextMatrixParser.parse(path.readText().replace('*','?')).matrix
        val result=RecoveryEngine().recoverWithDiagnostics(m,knownBitErrorLimit=2,maxKnownDataEccMismatches=2)
        assertTrue(result.candidates.any { it.text=="QRevy repaired." })
    }

    @Test fun largerSingleBlocksRecoverWithinTheSharedBudget() {
        for (version in 3..5) {
            val qr=Encoder.encode("abcdefghijklm",ErrorCorrectionLevel.L,
                mapOf(EncodeHintType.QR_VERSION to version,EncodeHintType.QR_MASK_PATTERN to 0))
            val m=QrMatrix(17+4*version).apply {
                for(y in 0 until dimension) for(x in 0 until dimension) set(x,y,
                    if(qr.matrix.get(x,y).toInt()==1)ModuleValue.ONE else ModuleValue.ZERO,1f,ModuleSource.IMAGE)
            }
            val ex=CodewordExtractor.extract(m,version,0)
            val de=QrBlockInterleaver.deinterleave(ex,version,ErrorCorrectionLevel.L)
            val data=de.blocks.single().numDataCodewords
            val erased=listOf(5,6)+(data until data+de.ecSymbolsPerBlock-4).toList()
            erased.forEach { raw -> ex.codewords[raw].coordinates.forEach { c -> m.set(c.x,c.y,ModuleValue.ERASED,0f,ModuleSource.IMAGE) } }
            for (raw in (ex.codewords.size-2) until ex.codewords.size) {
                val c=ex.codewords[raw].coordinates[0]
                m.set(c.x,c.y,if(m[c.x,c.y].value==ModuleValue.ONE)ModuleValue.ZERO else ModuleValue.ONE,1f,ModuleSource.IMAGE)
            }
            val observed=QrBlockInterleaver.deinterleave(CodewordExtractor.extract(m,version,0),version,ErrorCorrectionLevel.L)
            val budget=KnownBitErrorBudget()
            var found=false
            val start=System.nanoTime()
            val stops=KnownBitQrSearch.run(observed,version,2,listOf(emptySet()),12,32L*1024*1024,budget,RecoveryCancellation.NONE) { c ->
                if(c.single().corrected.contentEquals(de.blocks.single().codewords)) found=true
            }
            assertTrue(found)
            assertTrue(stops.isEmpty())
            println("Known-bit version=$version trials=${budget.trials} elapsedMs=${(System.nanoTime()-start)/1e6}")
        }
    }

}
