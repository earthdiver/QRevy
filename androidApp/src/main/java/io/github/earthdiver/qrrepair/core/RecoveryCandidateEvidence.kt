package io.github.earthdiver.qrrepair.core

/** Candidate comparisons and lazy provenance keep original observations distinct from search guesses. */
internal object RecoveryCandidateEvidence {
    fun userPinConflicts(matrix: QrMatrix, extraction: CodewordExtraction, correctedRaw: IntArray, mask: Int): List<ManualCellConflict> {
        val conflicts = mutableListOf<ManualCellConflict>()
        for (i in correctedRaw.indices) {
            val corrected = correctedRaw[i]
            for (b in 0 until 8) {
                val c = extraction.codewords[i].coordinates[b]
                val cell = matrix[c.x, c.y]
                if (cell.source != ModuleSource.USER) continue
                val observed = cell.value.bitOrNull() ?: continue
                val unmasked = (corrected shr (7 - b)) and 1
                val expectedObserved = unmasked xor if (CodewordExtractor.maskApplies(mask, c.x, c.y)) 1 else 0
                if (observed != expectedObserved) conflicts += ManualCellConflict(c, cell.value,
                    if (expectedObserved == 1) ModuleValue.ONE else ModuleValue.ZERO)
            }
        }
        return conflicts
    }

    fun countKnownBitMismatches(matrix: QrMatrix, extraction: CodewordExtraction, correctedRaw: IntArray, mask: Int): Pair<Int, Int> {
        var known = 0
        var mismatches = 0
        for (i in correctedRaw.indices) {
            val corrected = correctedRaw[i]
            for (b in 0 until 8) {
                val c = extraction.codewords[i].coordinates[b]
                val observed = matrix[c.x, c.y].value.bitOrNull() ?: continue
                known++
                val unmasked = (corrected shr (7 - b)) and 1
                val expectedObserved = unmasked xor if (CodewordExtractor.maskApplies(mask, c.x, c.y)) 1 else 0
                if (observed != expectedObserved) mismatches++
            }
        }
        return known to mismatches
    }

    fun repairInfo(
        rawLocations: Array<RawCodewordLocation?>,
        selectedRoutes: List<PartialCodewordSolver.Derivation>,
        originalUnknownByRaw: IntArray,
        postConstraintUnknownByRaw: IntArray,
        constraintKinds: Map<Pair<Int, Int>, PayloadConstraintSolver.ConstraintKind>,
        constraintValues: Map<Pair<Int, Int>, Int>,
    ): List<CodewordRepairInfo> = DeferredRepairInfo(rawLocations.size) { raw ->
        val loc = requireNotNull(rawLocations[raw])
        val perBit = buildMap {
            for (bit in 0 until 8) constraintKinds[raw to bit]?.let { put(bit, it) }
        }
        CodewordRepairInfo(
            rawIndex = raw,
            blockIndex = loc.blockIndex,
            localIndex = loc.localIndex,
            originalUnknownMask = originalUnknownByRaw[raw],
            postConstraintUnknownMask = postConstraintUnknownByRaw[raw],
            rsErrata = loc.localIndex in selectedRoutes[loc.blockIndex].errataPositions,
            partialEnumerated = loc.localIndex in selectedRoutes[loc.blockIndex].guessedPositions,
            constraintKindsByBit = perBit,
            bitConstraintSolved = selectedRoutes[loc.blockIndex].bitConstraintSolved,
            listDecoding = selectedRoutes[loc.blockIndex].listDecoding,
            constraintValuesByBit = buildMap {
                for (bit in 0 until 8) constraintValues[raw to bit]?.let { put(bit, it) }
            },
        )
    }
}
