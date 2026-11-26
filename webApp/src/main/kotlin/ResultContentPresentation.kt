package qrevy.web

import io.github.earthdiver.qrrepair.core.QrContentPreviewParser
import io.github.earthdiver.qrrepair.core.RecoveryCandidate
import io.github.earthdiver.qrrepair.core.resultContentText

internal fun resultContentPresentation(candidate: RecoveryCandidate): Map<String, String> {
    val preview = QrContentPreviewParser.parse(candidate) ?: return emptyMap()
    var contentStart = -1
    var contentEnd = -1
    val description = preview.resultContentText(candidate.text) { start, end ->
        contentStart = start
        contentEnd = end
    }
    return if (contentStart >= 0) mapOf(
        "before" to description.substring(0, contentStart),
        "after" to description.substring(contentEnd),
    ) else mapOf("before" to description, "after" to "")
}
