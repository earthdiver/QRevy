package io.github.earthdiver.qrrepair

import io.github.earthdiver.qrrepair.core.PreprocessSettings
import io.github.earthdiver.qrrepair.core.searchSummary
import io.github.earthdiver.qrrepair.localizedText

internal data class PreprocessDraftEvent(
    val key: String,
    val label: String,
    val detail: String,
    val settings: PreprocessSettings,
)

/** Tracks coalesced, unsaved pre-recovery setting changes independently of Android views. */
internal class PreprocessDraftState(
    initialSettings: PreprocessSettings,
    private val maxEvents: Int = 20,
) {
    private val pending = mutableListOf<PreprocessDraftEvent>()
    var lastSavedSettings: PreprocessSettings = initialSettings
        private set

    val events: List<PreprocessDraftEvent> get() = pending.toList()

    fun copyForEditing(): PreprocessDraftState = PreprocessDraftState(lastSavedSettings, maxEvents).also {
        it.pending.addAll(pending)
    }

    fun note(key: String, label: String, detail: String, settings: PreprocessSettings) {
        val event = PreprocessDraftEvent(key, label, detail, settings)
        if (pending.lastOrNull()?.key == key) pending[pending.lastIndex] = event else pending += event
        while (pending.size > maxEvents) pending.removeAt(0)
    }

    fun hasUnsavedChanges(current: PreprocessSettings): Boolean =
        current != lastSavedSettings || pending.isNotEmpty()

    fun commit(
        current: PreprocessSettings,
        record: (PreprocessDraftEvent) -> Unit,
    ) {
        pending.forEach(record)
        if (current != lastSavedSettings && pending.lastOrNull()?.settings != current) {
            record(PreprocessDraftEvent(
                key = "commit",
                label = localizedText("修復設定を確定", "Confirmed repair settings"),
                detail = current.searchSummary(),
                settings = current,
            ))
        }
        pending.clear()
        lastSavedSettings = current
    }
}
