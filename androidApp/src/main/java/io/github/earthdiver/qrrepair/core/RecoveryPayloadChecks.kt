package io.github.earthdiver.qrrepair.core

import com.google.zxing.DecodeHintType

/** One policy for symbolic branches, block prefixes and completed candidates within a trial. */
internal class RecoveryPayloadChecks(
    val assumeUtf8: Boolean = false,
    private val onRejection: (RecoveryCandidateRejection) -> Unit = {},
) {
    val decodeHints = if (assumeUtf8) mapOf(DecodeHintType.CHARACTER_SET to "UTF-8") else null

    fun acceptPrefix(data: ByteArray, version: Int): Boolean {
        val reason = rejection(data, version, prefix = true) ?: return true
        onRejection(reason)
        return false
    }

    fun rejection(data: ByteArray, version: Int, prefix: Boolean = false,
        onAssumedSegment: () -> Unit = {}): RecoveryCandidateRejection? {
        if (!assumeUtf8) {
            val valid = if (prefix) StrictQrPayloadValidator.canBePrefix(data, version)
                else StrictQrPayloadValidator.isValid(data, version)
            return if (valid) null else RecoveryCandidateRejection.QR_STRUCTURE
        }
        var reason = RecoveryCandidateRejection.QR_STRUCTURE
        val valid = StrictQrPayloadValidator.checkRecoveryData(data, version, prefix, assumeUtf8,
            onInvalidUtf8 = { reason = RecoveryCandidateRejection.UTF8_BYTE_SEGMENT },
            onAssumedSegment = onAssumedSegment)
        return if (valid) null else reason
    }

    fun traversal(sources: List<RsCandidateSource>, version: Int, nodes: Int, cancellation: RecoveryCancellation) =
        RsPayloadTraversal(sources, version, nodes, cancellation, assumeUtf8) {
            onRejection(RecoveryCandidateRejection.UTF8_BYTE_SEGMENT)
        }
}
