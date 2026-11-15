package io.github.earthdiver.qrrepair.core

/** Sequences one invocation's search phases while preserving its budgets and partial evidence. */
internal class RecoveryPhaseRunner(
    private val run: RecoveryRunState,
    private val maxKnownDataEccMismatches: Int?,
    private val cancellation: RecoveryCancellation,
    private val stopRequested: () -> Boolean,
    private val searchPhase: () -> Unit,
    private val finish: (List<RecoveryCandidate>) -> List<RecoveryCandidate>,
    private val executionPolicy: RecoveryExecutionPolicy = RecoveryExecutionPolicy.DETAILED,
    private val stopIsTimeLimit: () -> Boolean = { false },
) {
    private companion object {
        const val EARLY_LIST_ALLOWANCE_MILLIS = 1_000L
    }

    fun execute(): List<RecoveryCandidate> {
        cancellation.check()
        return try {
            if (executionPolicy != RecoveryExecutionPolicy.DETAILED) return automatic()
            if (run.listDecodingAdditionalErrors == 0) skipLists(ListDecodingStatus.DISABLED)
            search(RecoveryPhase.ORIGINAL_RS)
            // This pass uses the original observations: no forced suffix or tentative bits.
            // Its validated result can suppress later searches, but not image-erasure retries.
            val originalRsParsed = run.candidates.results().any { candidate ->
                candidate.payloadParsed && candidate.conventionalRsDiagnostic?.let {
                    it.succeeded && it.matches(candidate.correctedInterleaved) == true
                } == true
            }
            val inputBlocked = run.earlyBlocker != null ||
                run.searchDiagnostic().inputBlocker == RecoveryInputBlocker.STRUCTURAL_FIXED_CELLS
            if (!inputBlocked && maxKnownDataEccMismatches != 0 && run.diagnosticInput.hasImageMargins) {
                search(RecoveryPhase.IMAGE_ERASURES)
            }
            run.originalBlocks.clear()
            run.originalBlockBytes = 0L
            when {
                inputBlocked -> skipLists(ListDecodingStatus.INPUT_CONSTRAINT)
                originalRsParsed -> skipLists(if (maxKnownDataEccMismatches == 0)
                    ListDecodingStatus.ZERO_ERROR_LIMIT else ListDecodingStatus.ORIGINAL_RS_SUCCEEDED)
                else -> {
                    if (maxKnownDataEccMismatches == 0) skipLists(ListDecodingStatus.ZERO_ERROR_LIMIT)
                    else searchLists(early = true)
                    // Independent passes keep a large tentative RS list from starving exact spaces.
                    search(RecoveryPhase.BIT_CONSTRAINTS)
                    if (maxKnownDataEccMismatches != 0) {
                        search(RecoveryPhase.PARTIAL_RS)
                        if (run.knownBitErrorLimit > 0) {
                            run.knownBitSearchAttempted = true
                            try { search(RecoveryPhase.KNOWN_BIT_ERRORS) } catch (limit: KnownBitSearchLimit) {
                                run.incompleteReasons += if (limit.timedOut) SearchIncompleteReason.KNOWN_BIT_SEARCH_TIME_LIMIT
                                    else SearchIncompleteReason.KNOWN_BIT_SEARCH_LIMIT
                            }
                        }
                        searchLists(early = false)
                    }
                }
            }
            run.finished = true
            run.completedSteps++
            finish(run.candidates.results())
        } catch (error: RecoveryCancelledException) {
            cancellation.check()
            if (!stopRequested()) throw error
            val timedOut = stopIsTimeLimit()
            skipLists(if (timedOut) ListDecodingStatus.REPAIR_TIME_LIMIT else ListDecodingStatus.INTERRUPTED)
            run.incompleteReasons += if (timedOut) SearchIncompleteReason.TIME_LIMIT else SearchIncompleteReason.USER_INTERRUPTED
            run.combinationSearch = run.combinationSearch.afterPass(setOf(
                if (timedOut) CombinationSearchStop.TIME_LIMIT else CombinationSearchStop.USER_INTERRUPTED))
            run.candidates.results()
        } catch (_: Exception) {
            skipLists(ListDecodingStatus.INTERNAL_ERROR)
            run.incompleteReasons += SearchIncompleteReason.INTERNAL_ERROR
            run.candidates.results()
        }
    }

    /** Only explicitly marked TXT cells authorize assignment passes during automatic scanning. */
    private fun automatic(): List<RecoveryCandidate> {
        if (executionPolicy == RecoveryExecutionPolicy.AUTOMATIC_EXACT) {
            skipLists(ListDecodingStatus.ZERO_ERROR_LIMIT)
            search(RecoveryPhase.BIT_CONSTRAINTS)
            run.finished = true
            run.completedSteps++
            return finish(run.candidates.results())
        }
        run.listDecodingStatuses[ListDecodingStage.ADDITIONAL] = ListDecodingStatus.DISABLED
        if (executionPolicy == RecoveryExecutionPolicy.AUTOMATIC_BASIC || run.listDecodingAdditionalErrors == 0) {
            run.listDecodingStatuses[ListDecodingStage.WITHOUT_ASSIGNMENTS] = ListDecodingStatus.DISABLED
        }
        search(RecoveryPhase.ORIGINAL_RS)
        fun hasCandidate() = run.candidates.results().any { it.isSelectableRecovery }
        if (!hasCandidate() && run.earlyBlocker == null) search(RecoveryPhase.BIT_CONSTRAINTS)
        if (executionPolicy == RecoveryExecutionPolicy.AUTOMATIC_EXTENDED && !hasCandidate() &&
            run.earlyBlocker == null && maxKnownDataEccMismatches != 0) {
            if (run.diagnosticInput.hasImageMargins) search(RecoveryPhase.IMAGE_ERASURES)
            if (!hasCandidate() && run.diagnosticInput.prioritySearchCoordinates().isNotEmpty())
                search(RecoveryPhase.PARTIAL_RS)
            if (!hasCandidate()) searchLists(early = true)
            if (!hasCandidate() && run.diagnosticInput.prioritySearchCoordinates().isNotEmpty())
                searchLists(early = false)
        }
        run.finished = true
        run.completedSteps++
        return finish(run.candidates.results())
    }

    private fun search(phase: RecoveryPhase) {
        run.phase = phase
        searchPhase()
    }

    private fun skipLists(reason: ListDecodingStatus) {
        run.listDecodingStatuses.keys.forEach { stage ->
            if (run.listDecodingStatuses[stage] == ListDecodingStatus.NOT_REACHED)
                run.listDecodingStatuses[stage] = reason
        }
    }

    private fun searchLists(early: Boolean) {
        if (run.listDecodingAdditionalErrors == 0) return
        val listDecodingBudget = run.listDecodingBudget
        val stage = if (early) ListDecodingStage.WITHOUT_ASSIGNMENTS else ListDecodingStage.ADDITIONAL
        val phase = if (early) RecoveryPhase.EARLY_LIST else RecoveryPhase.ADDITIONAL_LIST
        val cap = minOf(maxKnownDataEccMismatches ?: Int.MAX_VALUE,
            ErrorLimitPolicy.listDecodingData((run.diagnosticInput.dimension - 17) / 4))
        val previous = run.listDecodingSummary ?: ListDecodingSummary()
        run.listDecodingSummary = previous.copy(mismatchLimits = previous.mismatchLimits + cap,
            extensionLimits = previous.extensionLimits + run.listDecodingAdditionalErrors)
        run.listDecodingStatuses[stage] = ListDecodingStatus.RUNNING
        run.listDecodingStops.clear()
        val before = listDecodingBudget.elapsedMillis
        try {
            if (early) listDecodingBudget.measureSlice(EARLY_LIST_ALLOWANCE_MILLIS) { search(phase) }
            else listDecodingBudget.measure { search(phase) }
        } catch (_: ListDecodingSliceLimitException) {
            run.listDecodingStatuses[stage] = ListDecodingStatus.DEFERRED
        } catch (_: ListDecodingTimeLimitException) {
            run.listDecodingStatuses[stage] = ListDecodingStatus.TIME_LIMIT
            run.incompleteReasons += SearchIncompleteReason.LIST_DECODING_TIME_LIMIT
        } catch (error: RecoveryCancelledException) {
            run.listDecodingStatuses[stage] = if (stopIsTimeLimit()) ListDecodingStatus.REPAIR_TIME_LIMIT else ListDecodingStatus.INTERRUPTED
            throw error
        } catch (error: Exception) {
            run.listDecodingStatuses[stage] = ListDecodingStatus.INTERNAL_ERROR
            throw error
        } finally {
            run.listDecodingSummary = run.listDecodingSummary?.let {
                it.copy(elapsedMillis = it.elapsedMillis + listDecodingBudget.elapsedMillis - before)
            }
            run.listDecodingNewTrials[stage] = (run.listDecodingSummary?.trials ?: previous.trials) - previous.trials
            if (run.listDecodingStatuses[stage] == ListDecodingStatus.RUNNING) {
                val current = requireNotNull(run.listDecodingSummary)
                run.listDecodingStatuses[stage] = when {
                    CombinationSearchStop.WORKING_MEMORY_LIMIT in run.listDecodingStops -> ListDecodingStatus.WORKING_MEMORY_LIMIT
                    run.listDecodingStops.isNotEmpty() -> ListDecodingStatus.SEARCH_LIMIT
                    // Count this stage independently; reusing a completed result is applicable work too.
                    current.trials == previous.trials && current.reusedTrials == previous.reusedTrials ->
                        ListDecodingStatus.INELIGIBLE
                    else -> ListDecodingStatus.COMPLETED
                }
            }
        }
    }
}
