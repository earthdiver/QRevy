package io.github.earthdiver.qrrepair

import io.github.earthdiver.qrrepair.core.PreprocessSettings
import io.github.earthdiver.qrrepair.core.RecoverySearchDiagnostic
import io.github.earthdiver.qrrepair.core.SearchIncompleteReason
import java.lang.ref.WeakReference

/** Prompt history is independent of task generations, which also change on identical reruns. */
internal class SingleSegmentRetryPolicy {
    private var source = WeakReference<Any>(null)
    private val offered = mutableSetOf<PreprocessSettings>()

    fun claim(input: Any, settings: PreprocessSettings, diagnostic: RecoverySearchDiagnostic?, interrupted: Boolean): Boolean {
        if (settings.trustKnownPayloadCount || interrupted || diagnostic == null ||
            !diagnostic.coverageComplete || diagnostic.decodedCandidate || !diagnostic.singleSegmentRetryAvailable ||
            SearchIncompleteReason.USER_INTERRUPTED in diagnostic.incompleteReasons ||
            SearchIncompleteReason.INTERNAL_ERROR in diagnostic.incompleteReasons) return false
        if (source.get() !== input) {
            source = WeakReference(input)
            offered.clear()
        }
        return offered.add(settings.recoverySnapshot())
    }
}
