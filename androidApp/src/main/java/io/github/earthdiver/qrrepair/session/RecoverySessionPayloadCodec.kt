package io.github.earthdiver.qrrepair.session

import com.google.zxing.qrcode.decoder.Version
import io.github.earthdiver.qrrepair.core.*
import io.github.earthdiver.qrrepair.session.RecoverySessionCodec.CorruptSessionException
import io.github.earthdiver.qrrepair.session.RecoverySessionCodec.MAX_HISTORY_ENTRIES
import io.github.earthdiver.qrrepair.session.RecoverySessionCodec.Payload
import io.github.earthdiver.qrrepair.session.RecoverySessionCodec.isSafeSessionId
import io.github.earthdiver.qrrepair.session.RecoverySessionCodec.isValidQrDimension
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.OutputStream

/** Supported session schemas, field validation, and exact serialized-size accounting. */
internal object RecoverySessionPayloadCodec {
    private const val MAX_TEXT = 4_096
    private const val MAX_DETAIL = 16_384
    private const val MAX_COORD_ABS = 10_000_000.0

    fun encodedPayloadBytes(payload: Payload): Long = encodedSize { write(it, payload) }

    fun encodedHistoryEntryBytes(entry: RecoveryHistoryEntry): Long = encodedSize { writeHistoryEntry(it, entry) }

    private fun encodedSize(write: (DataOutputStream) -> Unit): Long {
        val stream = SizeOutputStream()
        SizingDataOutputStream(stream).use(write)
        return stream.written
    }

    private class SizeOutputStream : OutputStream() {
        var written: Long = 0
        override fun write(value: Int) { written++ }
        override fun write(bytes: ByteArray, offset: Int, length: Int) { written += length }
    }

    private class SizingDataOutputStream(val counter: SizeOutputStream) : DataOutputStream(counter)

    fun read(raw: ByteArray, formatVersion: Int): Payload = DataInputStream(BufferedInputStream(ByteArrayInputStream(raw))).use { input ->
        val payload = readPayloadBody(input, formatVersion)
        requireCorrupt(input.available() == 0, "trailing bytes in session payload")
        payload
    }

    fun write(out: DataOutputStream, payload: Payload) {
        out.writeUTF(payload.id)
        out.writeLong(payload.updatedAtMs)
        out.writeInt(payload.uiMode.ordinal)
        out.writeInt(payload.historyCursor)
        out.writeInt(payload.historyEntries.size)
        payload.historyEntries.forEach { writeHistoryEntry(out, it) }
    }

    private fun readPayloadBody(input: DataInputStream, formatVersion: Int): Payload {
        val id = input.readUTF()
        requireCorrupt(isSafeSessionId(id), "invalid session id")
        val updated = input.readLong()
        requireCorrupt(updated >= 0, "invalid update timestamp")
        val mode = enumStrict<UiMode>(input.readInt(), "ui mode")
        val cursor = input.readInt()
        val count = input.readInt()
        requireCorrupt(count in 0..MAX_HISTORY_ENTRIES, "invalid history count")
        if (count == 0) requireCorrupt(cursor == -1, "empty history must have cursor -1")
        else requireCorrupt(cursor in 0 until count, "invalid history cursor")
        val history = ArrayList<RecoveryHistoryEntry>(count)
        repeat(count) { history += readHistoryEntry(input, formatVersion) }
        return Payload(id, updated, mode, history, cursor)
    }

    fun validate(payload: Payload) {
        require(isSafeSessionId(payload.id)) { "invalid session id" }
        require(payload.updatedAtMs >= 0)
        require(payload.historyEntries.size <= MAX_HISTORY_ENTRIES)
        if (payload.historyEntries.isEmpty()) require(payload.historyCursor == -1)
        else require(payload.historyCursor in payload.historyEntries.indices)
        payload.historyEntries.forEach { validateSnapshot(it.snapshot) }
    }

    private fun writeHistoryEntry(out: DataOutputStream, entry: RecoveryHistoryEntry) {
        require(entry.id.length <= MAX_TEXT) { "history id too long" }
        require(entry.timestampMs >= 0) { "invalid history timestamp" }
        require(entry.label.length <= 400) { "history label too long" }
        require(entry.detail.length <= 1600) { "history detail too long" }
        out.writeUTF(entry.id)
        out.writeLong(entry.timestampMs)
        out.writeInt(entry.kind.ordinal)
        out.writeUTF(entry.label)
        out.writeUTF(entry.detail)
        writeSnapshot(out, entry.snapshot)
    }

    private fun readHistoryEntry(input: DataInputStream, formatVersion: Int): RecoveryHistoryEntry {
        val id = input.readUTF()
        requireCorrupt(id.length <= MAX_TEXT, "history id too long")
        val timestamp = input.readLong()
        requireCorrupt(timestamp >= 0, "invalid history timestamp")
        val kind = enumStrict<HistoryEventKind>(input.readInt(), "history kind")
        val label = input.readUTF()
        val detail = input.readUTF()
        requireCorrupt(label.length <= 400 && detail.length <= 1600, "history text too long")
        return RecoveryHistoryEntry(id, timestamp, kind, label, detail, readSnapshot(input, formatVersion))
    }

    private fun writeSnapshot(out: DataOutputStream, snapshot: RecoverySnapshot) {
        if (out !is SizingDataOutputStream) validateSnapshot(snapshot)
        writeGeometry(out, snapshot.geometry)
        writeMatrix(out, snapshot.sourceMatrix)
        writeMatrix(out, snapshot.workingMatrix)
        out.writeInt(snapshot.recoveryIndex)
        out.writeBoolean(snapshot.preprocessSettings != null)
        snapshot.preprocessSettings?.let { writePreprocessSettings(out, it) }
        out.writeBoolean(snapshot.selectedQr != null)
        snapshot.selectedQr?.let { selection ->
            out.writeInt(selection.version)
            out.writeInt(selection.ecLevel)
            out.writeInt(selection.mask)
            val codewords = selection.codewordBytes()
            out.writeInt(codewords.size)
            out.write(codewords)
        }
        out.writeBoolean(snapshot.samplingInverted != null)
        snapshot.samplingInverted?.let(out::writeBoolean)
        out.writeBoolean(snapshot.oneTimeSingleSegment)
    }

    private fun readSnapshot(input: DataInputStream, formatVersion: Int): RecoverySnapshot {
        val geometry = readGeometry(input)
        val source = readMatrix(input)
        val working = readMatrix(input)
        requireCorrupt(source == null || validPrioritySearch(source), "invalid source priority-search modules")
        requireCorrupt(working == null || validPrioritySearch(working), "invalid working priority-search modules")
        if (source != null && working != null) requireCorrupt(source.dimension == working.dimension, "matrix dimension mismatch")
        geometry?.let { g ->
            val d = source?.dimension ?: working?.dimension
            if (d != null) requireCorrupt(g.dimension == d, "geometry/matrix dimension mismatch")
        }
        val recoveryIndex = input.readInt()
        requireCorrupt(recoveryIndex >= -1 && recoveryIndex <= 100_000, "invalid recovery index")
        val settings = if (input.readBoolean()) readPreprocessSettings(input, formatVersion) else null
        val selectedQr = if (input.readBoolean()) {
            val version = input.readInt()
            val ecLevel = input.readInt()
            val mask = input.readInt()
            requireCorrupt(version in 1..40, "invalid selected QR version")
            requireCorrupt(ecLevel in 0..3, "invalid selected QR ECC")
            requireCorrupt(mask in 0..7, "invalid selected QR mask")
            val length = input.readInt()
            requireCorrupt(length == Version.getVersionForNumber(version).totalCodewords, "invalid selected QR codeword count")
            val codewords = ByteArray(length)
            input.readFully(codewords)
            requireCorrupt(source == null || source.dimension == 17 + 4 * version, "selected QR/source mismatch")
            requireCorrupt(working == null || working.dimension == 17 + 4 * version, "selected QR/working mismatch")
            requireCorrupt(geometry == null || geometry.version == version, "selected QR/geometry mismatch")
            RecoverySelectionIdentity(version, ecLevel, mask, codewords)
        } else null
        val samplingInverted = if (input.readBoolean()) input.readBoolean() else null
        val oneTimeSingleSegment = if (formatVersion >= 27) {
            val flag = input.readUnsignedByte()
            requireCorrupt(flag in 0..1, "invalid runtime single-segment flag")
            flag == 1
        } else false
        return RecoverySnapshot(geometry, source, working, recoveryIndex, settings, selectedQr, samplingInverted, oneTimeSingleSegment)
    }

    private fun validateSnapshot(snapshot: RecoverySnapshot) {
        val d = snapshot.workingMatrix?.dimension ?: snapshot.sourceMatrix?.dimension
        if (snapshot.sourceMatrix != null && snapshot.workingMatrix != null) require(snapshot.sourceMatrix.dimension == snapshot.workingMatrix.dimension)
        snapshot.sourceMatrix?.let { require(validPrioritySearch(it)) }
        snapshot.workingMatrix?.let { require(validPrioritySearch(it)) }
        snapshot.geometry?.let { g -> if (d != null) require(g.dimension == d) }
        require(snapshot.recoveryIndex in -1..100_000)
        snapshot.preprocessSettings?.let(::validatePreprocessSettings)
        for (matrix in listOfNotNull(snapshot.sourceMatrix, snapshot.workingMatrix)) {
            for (y in 0 until matrix.dimension) for (x in 0 until matrix.dimension) {
                require(matrix[x, y].confidence.isFinite() && matrix[x, y].confidence in 0f..1f)
            }
        }
        snapshot.selectedQr?.let { selection ->
            require(d == null || 17 + 4 * selection.version == d)
            require(snapshot.geometry == null || snapshot.geometry.version == selection.version)
        }
    }

    private fun validPrioritySearch(matrix: QrMatrix): Boolean {
        val priority = matrix.prioritySearchCoordinates()
        if (priority.size > 14) return false
        if (priority.isEmpty()) return true
        val version = (matrix.dimension - 17) / 4
        val codewordCoords = runCatching {
            CodewordExtractor.extract(matrix, version, 0).codewords
                .flatMapTo(hashSetOf()) { partial -> partial.coordinates.map { it.x to it.y } }
        }.getOrNull() ?: return false
        return priority.all { it in codewordCoords }
    }

    private fun writePreprocessSettings(out: DataOutputStream, settings: PreprocessSettings) {
        validatePreprocessSettings(settings)
        out.writeFloat(settings.rotationDegrees)
        out.writeInt(settings.polarity.ordinal)
        out.writeInt(settings.blackMax)
        out.writeInt(settings.whiteMin)
        out.writeBoolean(settings.useUnknownColorClusters)
        out.writeInt(settings.unknownColorClusters.size)
        settings.unknownColorClusters.forEach { cluster ->
            out.writeInt(cluster.l100); out.writeInt(cluster.a100); out.writeInt(cluster.b100); out.writeInt(cluster.maxDeltaE100)
        }
        out.writeBoolean(settings.quietZoneCorrection)
        out.writeInt(settings.version)
        out.writeBoolean(settings.lockCorners)
        out.writeBoolean(settings.lockVersion)
        out.writeBoolean(settings.searchQuadNeighborhood)
        out.writeBoolean(settings.trustKnownPayloadCount)
        out.writeBoolean(settings.lockManualBits)
        out.writeBoolean(settings.formatConstraint != null)
        settings.formatConstraint?.let { constraint ->
            out.writeBoolean(constraint.ecLevel != null)
            constraint.ecLevel?.let { out.writeInt(it.bits) }
            out.writeBoolean(constraint.dataMask != null)
            constraint.dataMask?.let(out::writeInt)
        }
        out.writeBoolean(settings.formatMismatchLimit != null)
        settings.formatMismatchLimit?.let(out::writeInt)
        out.writeBoolean(settings.dataEccMismatchLimit != null)
        settings.dataEccMismatchLimit?.let(out::writeInt)
        out.writeBoolean(settings.perspectiveCorrectionQuad != null)
        settings.perspectiveCorrectionQuad?.asList()?.forEach { point ->
            out.writeDouble(point.x); out.writeDouble(point.y)
        }
        out.writeBoolean(settings.manualQuad != null)
        settings.manualQuad?.asList()?.forEach { point ->
            out.writeDouble(point.x); out.writeDouble(point.y)
        }
        out.writeBoolean(settings.logicalGridAssigned)
        out.writeInt(settings.manualFinders.size)
        settings.manualFinders.forEach { (role, point) ->
            out.writeInt(role.ordinal); out.writeDouble(point.x); out.writeDouble(point.y)
        }
        out.writeInt(settings.detectedFinders.size)
        settings.detectedFinders.forEach { finder ->
            out.writeDouble(finder.centerX); out.writeDouble(finder.centerY)
            out.writeDouble(finder.moduleSize); out.writeDouble(finder.angleRad); out.writeDouble(finder.score)
            out.writeInt(finder.observations); out.writeInt(finder.evidence.ordinal)
            out.writeInt(finder.polarity.ordinal); out.writeInt(finder.detectionSource.ordinal)
        }
        out.writeInt(settings.confirmedFinderPolarity.ordinal)
        out.writeInt(settings.preRecoveryEdits.size)
        settings.preRecoveryEdits.forEach { (coord, value) ->
            out.writeInt(coord.x); out.writeInt(coord.y); out.writeInt(value.ordinal)
        }
        out.writeInt(settings.preRecoveryPrioritySearch.size)
        settings.preRecoveryPrioritySearch.forEach { coord -> out.writeInt(coord.x); out.writeInt(coord.y) }
        out.writeInt(settings.preRecoverySoftEdits.size)
        settings.preRecoverySoftEdits.forEach { coord -> out.writeInt(coord.x); out.writeInt(coord.y) }
        out.writeBoolean(settings.automaticThresholds)
        out.writeInt(settings.provisionalBlackMax)
        out.writeInt(settings.provisionalWhiteMin)
        out.writeInt(settings.formatMismatchLimitMode.ordinal)
        out.writeInt(settings.dataEccMismatchLimitMode.ordinal)
        out.writeInt(settings.imageAdjustment.contrast)
        out.writeInt(settings.imageAdjustment.brightness)
        out.writeBoolean(settings.imageAdjustment.inverted)
        out.writeInt(settings.prioritySearchLimit)
        out.writeInt(settings.partialBitSearchLimit)
        out.writeBoolean(settings.mirroredInput)
        out.writeInt(settings.listDecodingAdditionalErrors)
    }

    private fun readPreprocessSettings(input: DataInputStream, formatVersion: Int): PreprocessSettings {
        val rotation = input.readFloat()
        requireCorrupt(rotation.isFinite() && rotation in -360f..360f, "invalid preprocessing rotation")
        val polarity = enumStrict<PolarityMode>(input.readInt(), "polarity")
        val blackMax = input.readInt(); val whiteMin = input.readInt()
        requireCorrupt(blackMax in 0..254 && whiteMin in 1..255 && blackMax < whiteMin, "invalid thresholds")
        val useUnknownColorClusters = input.readBoolean()
        val clusterCount = input.readInt()
        requireCorrupt(clusterCount in 0..4, "invalid unknown color cluster count")
        val unknownColorClusters = buildList {
            repeat(clusterCount) {
                try {
                    add(UnknownColorCluster(input.readInt(), input.readInt(), input.readInt(), input.readInt()))
                } catch (e: IllegalArgumentException) {
                    throw CorruptSessionException("invalid unknown color cluster", e)
                }
            }
        }
        val quietZone = input.readBoolean()
        val version = input.readInt()
        requireCorrupt(version in 1..40, "invalid preprocessing version")
        val lockCorners = input.readBoolean()
        val lockVersion = input.readBoolean()
        val searchQuadNeighborhood = input.readBoolean()
        val trustKnownPayloadCount = input.readBoolean()
        val lockManualBits = input.readBoolean()
        val formatConstraint = if (input.readBoolean()) {
            val ecLevel = if (input.readBoolean()) {
                val ecBits = input.readInt()
                requireCorrupt(ecBits in 0..3, "invalid format constraint ECC level")
                com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.forBits(ecBits)
            } else null
            val dataMask = if (input.readBoolean()) {
                input.readInt().also { requireCorrupt(it in 0..7, "invalid format constraint data mask") }
            } else null
            requireCorrupt(ecLevel != null || dataMask != null, "empty format constraint")
            QrFormatConstraint(ecLevel, dataMask)
        } else null
        val formatMismatchLimit = if (input.readBoolean()) {
            input.readInt().also { requireCorrupt(it in 0..30, "invalid format-information error limit") }
        } else null
        val dataEccMismatchLimit = if (input.readBoolean()) {
            input.readInt().also { requireCorrupt(it in 0..MAX_QR_DATA_ECC_BITS, "invalid data/ECC error limit") }
        } else null
        val perspectiveQuad = if (input.readBoolean()) {
            val points = List(4) { PointD(input.readDouble(), input.readDouble()) }
            ImageQuad(points[0], points[1], points[2], points[3])
        } else null
        val quad = if (input.readBoolean()) {
            val points = List(4) { PointD(input.readDouble(), input.readDouble()) }
            ImageQuad(points[0], points[1], points[2], points[3])
        } else null
        val logicalGridAssigned = input.readBoolean()
        val finderCount = input.readInt()
        requireCorrupt(finderCount in 0..3, "invalid confirmed finder count")
        val manualFinders = linkedMapOf<FinderRole, PointD>()
        repeat(finderCount) {
            val role = enumStrict<FinderRole>(input.readInt(), "finder role")
            requireCorrupt(role !in manualFinders, "duplicate confirmed finder role")
            manualFinders[role] = PointD(input.readDouble(), input.readDouble())
        }
        val candidateCount = input.readInt()
        requireCorrupt(candidateCount in 0..64, "invalid finder candidate count")
        val candidates = ArrayList<FinderCandidate>(candidateCount)
        repeat(candidateCount) {
            candidates += FinderCandidate(
                input.readDouble(), input.readDouble(), input.readDouble(), input.readDouble(), input.readDouble(),
                input.readInt(), enumStrict(input.readInt(), "finder evidence"),
                enumStrict(input.readInt(), "finder polarity"), enumStrict(input.readInt(), "finder detection source"),
            )
        }
        val confirmedPolarity = enumStrict<FinderPolarity>(input.readInt(), "confirmed finder polarity")
        val editCount = input.readInt()
        val dimension = 17 + 4 * version
        requireCorrupt(editCount in 0..dimension * dimension, "invalid preprocessing edit count")
        val edits = linkedMapOf<Coord, ModuleValue>()
        repeat(editCount) {
            val coord = Coord(input.readInt(), input.readInt())
            requireCorrupt(coord.x in 0 until dimension && coord.y in 0 until dimension, "preprocessing edit outside matrix")
            requireCorrupt(coord !in edits, "duplicate preprocessing edit")
            edits[coord] = enumStrict(input.readInt(), "preprocessing module value")
        }
        val priorityCount = input.readInt()
        requireCorrupt(priorityCount in 0..14, "invalid priority-search count")
        val priority = buildSet {
            repeat(priorityCount) {
                val coord = Coord(input.readInt(), input.readInt())
                requireCorrupt(coord.x in 0 until dimension && coord.y in 0 until dimension, "priority-search coordinate outside matrix")
                requireCorrupt(add(coord), "duplicate priority-search coordinate")
            }
        }
        val softEditCount = input.readInt()
        requireCorrupt(softEditCount in 0..editCount, "invalid tentative edit count")
        val softEdits = buildSet {
            repeat(softEditCount) {
                val coord = Coord(input.readInt(), input.readInt())
                requireCorrupt(coord in edits, "tentative coordinate has no edit")
                requireCorrupt(add(coord), "duplicate tentative coordinate")
            }
        }
        val automaticThresholds = input.readBoolean()
        val provisionalBlackMax = input.readInt()
        val provisionalWhiteMin = input.readInt()
        requireCorrupt(provisionalBlackMax in 0..254 && provisionalWhiteMin in 1..255 &&
            provisionalBlackMax < provisionalWhiteMin, "invalid provisional thresholds")
        val formatMismatchLimitMode = enumStrict<ErrorLimitMode>(input.readInt(), "format-information error limit mode")
        val dataEccMismatchLimitMode = enumStrict<ErrorLimitMode>(input.readInt(), "data/ECC error limit mode")
        val contrast = input.readInt()
        val brightness = input.readInt()
        val inverted = input.readUnsignedByte()
        requireCorrupt(contrast in -100..100 && brightness in -100..100 && inverted in 0..1, "invalid image adjustment")
        val adjustment = ImageAdjustment(contrast, brightness, inverted == 1)
        return try {
            PreprocessSettings(
                imageAdjustment = adjustment,
                prioritySearchLimit = input.readInt(),
                partialBitSearchLimit = if (formatVersion >= 24) input.readInt() else PartialBitSearchLimit.DEFAULT,
                mirroredInput = if (formatVersion >= 25) {
                    val value = input.readUnsignedByte()
                    requireCorrupt(value in 0..1, "invalid mirrored input flag")
                    value == 1
                } else false,
                listDecodingAdditionalErrors = if (formatVersion >= 26) input.readInt() else ListDecodingLimit.DEFAULT,
                rotationDegrees = rotation,
                polarity = polarity,
                automaticThresholds = automaticThresholds,
                provisionalBlackMax = provisionalBlackMax,
                provisionalWhiteMin = provisionalWhiteMin,
                blackMax = blackMax,
                whiteMin = whiteMin,
                unknownColorClusters = unknownColorClusters,
                useUnknownColorClusters = useUnknownColorClusters,
                quietZoneCorrection = quietZone,
                version = version,
                lockCorners = lockCorners,
                lockVersion = lockVersion,
                searchQuadNeighborhood = searchQuadNeighborhood,
                trustKnownPayloadCount = trustKnownPayloadCount,
                formatConstraint = formatConstraint,
                formatMismatchLimit = formatMismatchLimit,
                dataEccMismatchLimit = dataEccMismatchLimit,
                formatMismatchLimitMode = formatMismatchLimitMode,
                dataEccMismatchLimitMode = dataEccMismatchLimitMode,
                lockManualBits = lockManualBits,
                perspectiveCorrectionQuad = perspectiveQuad,
                manualQuad = quad,
                manualFinders = manualFinders,
                logicalGridAssigned = logicalGridAssigned,
                detectedFinders = candidates,
                confirmedFinderPolarity = confirmedPolarity,
                preRecoveryEdits = edits,
                preRecoverySoftEdits = softEdits,
                preRecoveryPrioritySearch = priority,
            )
        } catch (e: IllegalArgumentException) {
            throw CorruptSessionException("invalid preprocessing settings", e)
        }
    }

    private fun validatePreprocessSettings(settings: PreprocessSettings) {
        require(settings.rotationDegrees.isFinite() && settings.rotationDegrees in -360f..360f)
        require(settings.version in 1..40)
        require(settings.blackMax in 0..254 && settings.whiteMin in 1..255 && settings.blackMax < settings.whiteMin)
        require(settings.unknownColorClusters.size <= 4)
        require(settings.manualFinders.size <= 3 && settings.detectedFinders.size <= 64)
        val dimension = 17 + 4 * settings.version
        require(settings.preRecoveryEdits.size <= dimension * dimension)
        require(settings.preRecoverySoftEdits.all { it in settings.preRecoveryEdits })
        require(settings.prioritySearchLimit in 4..14)
        require(settings.partialBitSearchLimit in PartialBitSearchLimit.supported)
        require(settings.listDecodingAdditionalErrors in ListDecodingLimit.supported)
        require(settings.preRecoveryPrioritySearch.size <= 14)
        require(settings.preRecoveryPrioritySearch.all { settings.preRecoveryEdits[it] == ModuleValue.ERASED })
        require(settings.formatMismatchLimit == null || settings.formatMismatchLimit in 0..30)
        require(settings.dataEccMismatchLimit == null || settings.dataEccMismatchLimit in 0..MAX_QR_DATA_ECC_BITS)
        require(!settings.searchQuadNeighborhood || (settings.lockCorners && settings.lockVersion))
        require(settings.preRecoveryPrioritySearch.isEmpty() || (settings.lockCorners && settings.lockVersion))
        fun validPoint(point: PointD) = point.x.isFinite() && point.y.isFinite() &&
            kotlin.math.abs(point.x) <= MAX_COORD_ABS && kotlin.math.abs(point.y) <= MAX_COORD_ABS
        settings.manualQuad?.asList()?.forEach { require(validPoint(it)) }
        settings.perspectiveCorrectionQuad?.asList()?.forEach { require(validPoint(it)) }
        settings.manualFinders.values.forEach { require(validPoint(it)) }
        settings.detectedFinders.forEach { finder ->
            require(validPoint(PointD(finder.centerX, finder.centerY)))
            require(finder.moduleSize.isFinite() && finder.moduleSize in 0.05..100_000.0)
            require(finder.angleRad.isFinite() && kotlin.math.abs(finder.angleRad) <= 1000.0)
            require(finder.score.isFinite() && kotlin.math.abs(finder.score) <= 1_000_000.0)
            require(finder.observations in 0..1_000_000)
        }
        settings.preRecoveryEdits.keys.forEach { coord ->
            require(coord.x in 0 until dimension && coord.y in 0 until dimension)
        }
    }

    private fun writeMatrix(out: DataOutputStream, matrix: QrMatrix?) {
        out.writeBoolean(matrix != null)
        if (matrix == null) return
        out.writeInt(matrix.dimension)
        if (out is SizingDataOutputStream) {
            out.counter.written += matrix.dimension.toLong() * matrix.dimension * 7
            return
        }
        for (y in 0 until matrix.dimension) for (x in 0 until matrix.dimension) {
            val c = matrix[x, y]
            require(c.confidence.isFinite() && c.confidence in 0f..1f)
            out.writeByte(c.value.ordinal)
            out.writeByte(c.source.ordinal)
            out.writeFloat(c.confidence)
            out.writeBoolean(c.prioritySearch)
        }
    }

    private fun readMatrix(input: DataInputStream): QrMatrix? {
        if (!input.readBoolean()) return null
        val d = input.readInt()
        requireCorrupt(isValidQrDimension(d), "invalid QR dimension $d")
        val matrix = QrMatrix(d)
        for (y in 0 until d) for (x in 0 until d) {
            val value = enumStrict<ModuleValue>(input.readUnsignedByte(), "module value")
            val source = enumStrict<ModuleSource>(input.readUnsignedByte(), "module source")
            val confidence = input.readFloat()
            requireCorrupt(confidence.isFinite() && confidence in 0f..1f, "invalid confidence")
            matrix.set(x, y, value, confidence, source)
            if (input.readBoolean()) {
                requireCorrupt(value == ModuleValue.ERASED, "priority-search module must be erased")
                matrix.setPrioritySearch(x, y, true)
            }
        }
        return matrix
    }

    private fun writeGeometry(out: DataOutputStream, g: GridGeometry?) {
        out.writeBoolean(g != null)
        if (g == null) return
        validateGeometry(g)
        out.writeInt(g.version)
        out.writeInt(g.role.ordinal)
        out.writeDouble(g.finderCenterX); out.writeDouble(g.finderCenterY)
        out.writeDouble(g.modulePitch); out.writeDouble(g.angleRad)
        out.writeInt(g.model.ordinal)
        out.writeBoolean(g.imageQuad != null)
        g.imageQuad?.asList()?.forEach { p -> out.writeDouble(p.x); out.writeDouble(p.y) }
    }

    private fun readGeometry(input: DataInputStream): GridGeometry? {
        if (!input.readBoolean()) return null
        val version = input.readInt()
        requireCorrupt(version in 1..40, "invalid geometry version")
        val role = enumStrict<FinderRole>(input.readInt(), "finder role")
        val cx = input.readDouble(); val cy = input.readDouble()
        val pitch = input.readDouble(); val angle = input.readDouble()
        val model = enumStrict<GeometryModel>(input.readInt(), "geometry model")
        val quad = if (input.readBoolean()) {
            val p = ArrayList<PointD>(4)
            repeat(4) { p += PointD(input.readDouble(), input.readDouble()) }
            ImageQuad(p[0], p[1], p[2], p[3])
        } else null
        val result = GridGeometry(version, role, cx, cy, pitch, angle, quad, model)
        requireCorrupt(runCatching { validateGeometry(result) }.isSuccess, "invalid geometry values")
        return result
    }

    private fun validateGeometry(g: GridGeometry) {
        require(g.version in 1..40)
        require(g.finderCenterX.isFinite() && kotlin.math.abs(g.finderCenterX) <= MAX_COORD_ABS)
        require(g.finderCenterY.isFinite() && kotlin.math.abs(g.finderCenterY) <= MAX_COORD_ABS)
        require(g.modulePitch.isFinite() && g.modulePitch in 0.05..100_000.0)
        require(g.angleRad.isFinite() && kotlin.math.abs(g.angleRad) <= 1000.0)
        g.imageQuad?.asList()?.forEach { p ->
            require(p.x.isFinite() && p.y.isFinite())
            require(kotlin.math.abs(p.x) <= MAX_COORD_ABS && kotlin.math.abs(p.y) <= MAX_COORD_ABS)
        }
    }

    private inline fun <reified T : Enum<T>> enumStrict(index: Int, label: String): T {
        val entries = enumValues<T>()
        if (index !in entries.indices) throw CorruptSessionException("invalid $label enum index $index")
        return entries[index]
    }

    private fun requireCorrupt(condition: Boolean, message: String) {
        if (!condition) throw CorruptSessionException(message)
    }
}
