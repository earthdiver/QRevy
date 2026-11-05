package io.github.earthdiver.qrrepair

import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.decoder.Version
import io.github.earthdiver.qrrepair.core.Coord
import io.github.earthdiver.qrrepair.core.ErrorLimitMode
import io.github.earthdiver.qrrepair.core.ErrorLimitPolicy
import io.github.earthdiver.qrrepair.core.FormatEditConflict
import io.github.earthdiver.qrrepair.core.FormatEditPolicy
import io.github.earthdiver.qrrepair.core.ModuleValue
import io.github.earthdiver.qrrepair.core.PreprocessSettings
import io.github.earthdiver.qrrepair.core.QrFormatConstraint

/** Editable search settings; rejected format changes leave the current selection intact. */
internal class PreprocessSearchSettingsState(initial: PreprocessSettings) {
    var formatConstraint = initial.formatConstraint
        private set
    var formatMismatchLimit = initial.formatMismatchLimit
        private set
    var formatMismatchLimitMode = initial.formatMismatchLimitMode
        private set
    var dataEccMismatchLimit = initial.dataEccMismatchLimit
        private set
    var dataEccMismatchLimitMode = initial.dataEccMismatchLimitMode
        private set
    var partialBitSearchLimit = initial.partialBitSearchLimit
    var listDecodingAdditionalErrors = initial.listDecodingAdditionalErrors
    var knownBitErrorLimit = initial.knownBitErrorLimit

    fun setFormatMismatchLimit(mode: ErrorLimitMode, value: Int?) {
        formatMismatchLimitMode = mode
        formatMismatchLimit = value
    }

    fun setDataEccMismatchLimit(mode: ErrorLimitMode, value: Int?) {
        dataEccMismatchLimitMode = mode
        dataEccMismatchLimit = value
    }

    fun effectiveFormatMismatchLimit(): Int? =
        ErrorLimitPolicy.format(formatMismatchLimitMode, formatMismatchLimit)

    fun effectiveDataEccMismatchLimit(version: Int): Int? =
        ErrorLimitPolicy.data(version, dataEccMismatchLimitMode, dataEccMismatchLimit)

    fun dataEccMaximum(version: Int): Int = Version.getVersionForNumber(version).totalCodewords * 8

    fun dataEccLimitIsValid(version: Int): Boolean =
        effectiveDataEccMismatchLimit(version)?.let { it <= dataEccMaximum(version) } != false

    fun formatConflict(version: Int, fixedEdits: Map<Coord, ModuleValue>): FormatEditConflict? =
        FormatEditPolicy.conflict(version, formatConstraint, fixedEdits)

    fun updateFormatConstraint(
        version: Int,
        ecLevel: ErrorCorrectionLevel?,
        dataMask: Int?,
        fixedEdits: Map<Coord, ModuleValue>,
    ): FormatEditConflict? {
        val next = if (ecLevel == null && dataMask == null) null else QrFormatConstraint(ecLevel, dataMask)
        // Relaxing a selection must remain possible even when a restored draft has conflicting pins.
        if (!FormatEditPolicy.returnsToAuto(formatConstraint, next)) {
            FormatEditPolicy.conflict(version, next, fixedEdits)?.let { return it }
        }
        formatConstraint = next
        return null
    }
}
