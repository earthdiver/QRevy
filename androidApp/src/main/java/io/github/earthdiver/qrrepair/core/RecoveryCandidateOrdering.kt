package io.github.earthdiver.qrrepair.core

/** Shared ranking for candidates within and across geometry attempts. */
internal val recoveryCandidateComparator = compareByDescending<RecoveryCandidate> { it.payloadParsed }
    .thenBy { it.formatMismatches }
    .thenByDescending { it.formatMatches }
    .thenBy { it.knownBitMismatchesAfterCorrection }
    .thenByDescending { it.knownBitsCompared }
    .thenByDescending { it.minRsMargin }
    .thenBy { it.partialGuessedBits }
    .thenBy { it.correctedErrataSymbols }
    .thenBy { it.erasedCodewords }

