package io.github.earthdiver.qrrepair.core

/** Chooses between algorithms covering the same hard-decision ball; costs are ordering heuristics. */
internal object ListDecodingPlanner {
    data class HardPlan(val method: ListDecoderMethod, val parameters: GuruswamiSudanDecoder.Parameters,
        val workingBytes: Long, val estimatedWork: Long)

    fun hard(length: Int, dataLength: Int, erasures: Int, availableBytes: Long, check: () -> Unit): HardPlan? {
        return hard(length, dataLength, erasures, availableBytes, ListDecodingLimit.DEFAULT, check)
    }

    fun hard(length: Int, dataLength: Int, erasures: Int, availableBytes: Long, additionalErrors: Int,
        check: () -> Unit): HardPlan? =
        hard(length, dataLength, erasures, availableBytes, additionalErrors, check, GuruswamiSudanDecoder(length, dataLength))

    fun hard(length: Int, dataLength: Int, erasures: Int, availableBytes: Long, additionalErrors: Int,
        check: () -> Unit, decoder: GuruswamiSudanDecoder): HardPlan? {
        require(decoder.length == length && decoder.dataLength == dataLength)
        val parameters = decoder.parameters(erasures, Long.MAX_VALUE, additionalErrors, check) ?: return null
        val width = parameters.listDegree + 1L
        // Triangular row-update estimate. Low-multiplicity GS can be cheaper than Wu's
        // field scan; the estimate orders equivalent searches and is not a time guarantee.
        val gsWork = listDecodingWork(width, width, parameters.degreeBound + 1L, parameters.degreeBound + 1L) / 2
        val wu = WuDecoder(length, dataLength)
        val wuWork = wu.estimatedWork(erasures, additionalErrors)
        if (wu.workingBytes <= availableBytes && (parameters.workingBytes > availableBytes || wuWork < gsWork)) {
            return HardPlan(ListDecoderMethod.WU, parameters, wu.workingBytes, wuWork)
        }
        if (parameters.workingBytes <= availableBytes) return HardPlan(ListDecoderMethod.GURUSWAMI_SUDAN, parameters, parameters.workingBytes, gsWork)
        throw ListDecodingWorkingMemoryException()
    }
}
