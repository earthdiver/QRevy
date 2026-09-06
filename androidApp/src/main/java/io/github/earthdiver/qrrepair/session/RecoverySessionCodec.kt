package io.github.earthdiver.qrrepair.session

import io.github.earthdiver.qrrepair.localizedText

import io.github.earthdiver.qrrepair.core.*
import java.io.*
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Android-independent, bounded and checksummed persistence codec for repair sessions. */
object RecoverySessionCodec {
    const val CURRENT_VERSION = 15
    const val MAX_HISTORY_ENTRIES = 40
    const val MAX_SESSION_PAYLOAD_BYTES = 16 * 1024 * 1024
    const val MAX_SESSION_FILE_BYTES = 20 * 1024 * 1024
    private const val MAGIC = 0x5152523A // QRR10
    private const val LEGACY_MAGIC_V9 = 0x51525239
    private const val HASH_BYTES = 32
    private const val MAX_TEXT = 4_096
    private const val MAX_DETAIL = 16_384
    private const val MAX_COORD_ABS = 10_000_000.0

    data class Payload(
        val id: String,
        val updatedAtMs: Long,
        val uiMode: UiMode,
        val historyEntries: List<RecoveryHistoryEntry>,
        val historyCursor: Int,
    )

    class CorruptSessionException(message: String, cause: Throwable? = null) : IOException(message, cause)

    fun encode(payload: Payload): ByteArray {
        validatePayload(payload)
        val raw = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(BufferedOutputStream(bytes)).use { out ->
                writePayload(out, payload)
            }
            bytes.toByteArray()
        }
        require(raw.size <= MAX_SESSION_PAYLOAD_BYTES) { "session payload too large" }
        val hash = sha256(raw)
        val compressed = ByteArrayOutputStream().use { bytes ->
            GZIPOutputStream(bytes).use { it.write(raw) }
            bytes.toByteArray()
        }
        require(compressed.size <= MAX_SESSION_FILE_BYTES) { "compressed session too large" }
        return ByteArrayOutputStream(8 + 8 + HASH_BYTES + compressed.size).use { bytes ->
            DataOutputStream(bytes).use { out ->
                out.writeInt(MAGIC)
                out.writeInt(CURRENT_VERSION)
                out.writeInt(raw.size)
                out.writeInt(compressed.size)
                out.write(hash)
                out.write(compressed)
            }
            bytes.toByteArray()
        }
    }

    /** Decodes only the current format. Older QRR sessions are intentionally invalid. */
    fun decode(fileBytes: ByteArray): Payload {
        if (fileBytes.size > MAX_SESSION_FILE_BYTES) throw CorruptSessionException("session file exceeds size limit")
        if (fileBytes.size < 4) throw CorruptSessionException("session file is truncated")
        return try {
            val magic = DataInputStream(ByteArrayInputStream(fileBytes)).readInt()
            if (magic == MAGIC || magic == LEGACY_MAGIC_V9) decodeCurrent(fileBytes)
            else throw CorruptSessionException(localizedText("旧形式のQRRセッションは読み込めません", "Legacy QRR sessions are not supported"))
        } catch (e: CorruptSessionException) {
            throw e
        } catch (e: Exception) {
            throw CorruptSessionException("cannot decode session", e)
        }
    }

    private fun decodeCurrent(fileBytes: ByteArray): Payload {
        DataInputStream(BufferedInputStream(ByteArrayInputStream(fileBytes))).use { input ->
            val magic = input.readInt()
            requireCorrupt(magic == MAGIC || magic == LEGACY_MAGIC_V9, "invalid QRR magic")
            val version = input.readInt()
            requireCorrupt(version in 10..CURRENT_VERSION || (magic == LEGACY_MAGIC_V9 && version == 9), localizedText("旧形式のQRRセッションは読み込めません（version $version）", "Legacy QRR session version $version is not supported"))
            val rawSize = input.readInt()
            val compressedSize = input.readInt()
            requireCorrupt(rawSize in 1..MAX_SESSION_PAYLOAD_BYTES, "invalid raw payload size")
            requireCorrupt(compressedSize in 1..MAX_SESSION_FILE_BYTES, "invalid compressed payload size")
            requireCorrupt(fileBytes.size == 16 + HASH_BYTES + compressedSize, "session length mismatch")
            val expectedHash = ByteArray(HASH_BYTES)
            input.readFully(expectedHash)
            val compressed = ByteArray(compressedSize)
            input.readFully(compressed)
            val raw = gunzipBounded(compressed, rawSize)
            requireCorrupt(MessageDigest.isEqual(expectedHash, sha256(raw)), "session SHA-256 mismatch")
            return parsePayload(raw, version)
        }
    }

    private fun parsePayload(raw: ByteArray, version: Int): Payload = DataInputStream(BufferedInputStream(ByteArrayInputStream(raw))).use { input ->
        val payload = readPayloadBody(
            input,
            hasPreprocessSettings = version >= 3,
            hasGeometryLock = version >= 4,
            hasPrioritySearch = version >= 5,
            hasPerspectiveCorrection = version >= 10,
            hasUnknownColorClusters = version >= 11,
            hasLogicalGridAssignment = version >= 12,
            hasFormatConstraint = version >= 13,
            hasFormatMismatchLimit = version >= 14,
            hasDataEccMismatchLimit = version >= 15,
        )
        requireCorrupt(input.available() == 0, "trailing bytes in session payload")
        payload
    }

    private fun writePayload(out: DataOutputStream, payload: Payload) {
        out.writeUTF(payload.id)
        out.writeLong(payload.updatedAtMs)
        out.writeInt(payload.uiMode.ordinal)
        out.writeInt(payload.historyCursor)
        out.writeInt(payload.historyEntries.size)
        payload.historyEntries.forEach { writeHistoryEntry(out, it) }
    }

    private fun readPayloadBody(
        input: DataInputStream,
        hasPreprocessSettings: Boolean,
        hasGeometryLock: Boolean,
        hasPrioritySearch: Boolean = false,
        hasPerspectiveCorrection: Boolean = false,
        hasUnknownColorClusters: Boolean = false,
        hasLogicalGridAssignment: Boolean = false,
        hasFormatConstraint: Boolean = false,
        hasFormatMismatchLimit: Boolean = false,
        hasDataEccMismatchLimit: Boolean = false,
    ): Payload {
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
        repeat(count) { history += readHistoryEntry(input, hasPreprocessSettings, hasGeometryLock, hasPrioritySearch, hasPerspectiveCorrection, hasUnknownColorClusters, hasLogicalGridAssignment, hasFormatConstraint, hasFormatMismatchLimit, hasDataEccMismatchLimit) }
        return Payload(id, updated, mode, history, cursor)
    }

    private fun validatePayload(payload: Payload) {
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

    private fun readHistoryEntry(
        input: DataInputStream,
        hasPreprocessSettings: Boolean,
        hasGeometryLock: Boolean,
        hasPrioritySearch: Boolean,
        hasPerspectiveCorrection: Boolean,
        hasUnknownColorClusters: Boolean,
        hasLogicalGridAssignment: Boolean,
        hasFormatConstraint: Boolean,
        hasFormatMismatchLimit: Boolean,
        hasDataEccMismatchLimit: Boolean,
    ): RecoveryHistoryEntry {
        val id = input.readUTF()
        requireCorrupt(id.length <= MAX_TEXT, "history id too long")
        val timestamp = input.readLong()
        requireCorrupt(timestamp >= 0, "invalid history timestamp")
        val kind = enumStrict<HistoryEventKind>(input.readInt(), "history kind")
        val label = input.readUTF()
        val detail = input.readUTF()
        requireCorrupt(label.length <= 400 && detail.length <= 1600, "history text too long")
        return RecoveryHistoryEntry(id, timestamp, kind, label, detail, readSnapshot(input, hasPreprocessSettings, hasGeometryLock, hasPrioritySearch, hasPerspectiveCorrection, hasUnknownColorClusters, hasLogicalGridAssignment, hasFormatConstraint, hasFormatMismatchLimit, hasDataEccMismatchLimit))
    }

    private fun writeSnapshot(out: DataOutputStream, snapshot: RecoverySnapshot) {
        validateSnapshot(snapshot)
        writeGeometry(out, snapshot.geometry)
        writeMatrix(out, snapshot.sourceMatrix)
        writeMatrix(out, snapshot.workingMatrix)
        out.writeInt(snapshot.recoveryIndex)
        out.writeBoolean(snapshot.preprocessSettings != null)
        snapshot.preprocessSettings?.let { writePreprocessSettings(out, it) }
    }

    private fun readSnapshot(
        input: DataInputStream,
        hasPreprocessSettings: Boolean,
        hasGeometryLock: Boolean,
        hasPrioritySearch: Boolean,
        hasPerspectiveCorrection: Boolean,
        hasUnknownColorClusters: Boolean,
        hasLogicalGridAssignment: Boolean,
        hasFormatConstraint: Boolean,
        hasFormatMismatchLimit: Boolean,
        hasDataEccMismatchLimit: Boolean,
    ): RecoverySnapshot {
        val geometry = readGeometry(input)
        val source = readMatrix(input, hasPrioritySearch)
        val working = readMatrix(input, hasPrioritySearch)
        requireCorrupt(source == null || validPrioritySearch(source), "invalid source priority-search modules")
        requireCorrupt(working == null || validPrioritySearch(working), "invalid working priority-search modules")
        if (source != null && working != null) requireCorrupt(source.dimension == working.dimension, "matrix dimension mismatch")
        geometry?.let { g ->
            val d = source?.dimension ?: working?.dimension
            if (d != null) requireCorrupt(g.dimension == d, "geometry/matrix dimension mismatch")
        }
        val recoveryIndex = input.readInt()
        requireCorrupt(recoveryIndex >= -1 && recoveryIndex <= 100_000, "invalid recovery index")
        val settings = if (hasPreprocessSettings && input.readBoolean()) readPreprocessSettings(input, hasGeometryLock, hasPrioritySearch, hasPerspectiveCorrection, hasUnknownColorClusters, hasLogicalGridAssignment, hasFormatConstraint, hasFormatMismatchLimit, hasDataEccMismatchLimit) else null
        return RecoverySnapshot(geometry, source, working, recoveryIndex, settings)
    }

    private fun validateSnapshot(snapshot: RecoverySnapshot) {
        val d = snapshot.workingMatrix?.dimension ?: snapshot.sourceMatrix?.dimension
        if (snapshot.sourceMatrix != null && snapshot.workingMatrix != null) require(snapshot.sourceMatrix.dimension == snapshot.workingMatrix.dimension)
        snapshot.sourceMatrix?.let { require(validPrioritySearch(it)) }
        snapshot.workingMatrix?.let { require(validPrioritySearch(it)) }
        snapshot.geometry?.let { g -> if (d != null) require(g.dimension == d) }
        require(snapshot.recoveryIndex in -1..100_000)
        snapshot.preprocessSettings?.let(::validatePreprocessSettings)
    }

    private fun validPrioritySearch(matrix: QrMatrix): Boolean {
        val priority = matrix.prioritySearchCoordinates()
        if (priority.size > 4) return false
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
        out.writeInt(settings.thresholdMode.ordinal)
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
    }

    private fun readPreprocessSettings(input: DataInputStream, hasGeometryLock: Boolean, hasPrioritySearch: Boolean, hasPerspectiveCorrection: Boolean, hasUnknownColorClusters: Boolean, hasLogicalGridAssignment: Boolean, hasFormatConstraint: Boolean, hasFormatMismatchLimit: Boolean, hasDataEccMismatchLimit: Boolean): PreprocessSettings {
        val rotation = input.readFloat()
        requireCorrupt(rotation.isFinite() && rotation in -360f..360f, "invalid preprocessing rotation")
        val polarity = enumStrict<PolarityMode>(input.readInt(), "polarity")
        val thresholdMode = enumStrict<ThresholdMode>(input.readInt(), "threshold mode")
        val blackMax = input.readInt(); val whiteMin = input.readInt()
        requireCorrupt(blackMax in 0..254 && whiteMin in 1..255 && blackMax < whiteMin, "invalid thresholds")
        val useUnknownColorClusters = if (hasUnknownColorClusters) input.readBoolean() else false
        val unknownColorClusters = if (hasUnknownColorClusters) {
            val count = input.readInt()
            requireCorrupt(count in 0..4, "invalid unknown color cluster count")
            buildList {
                repeat(count) {
                    try {
                        add(UnknownColorCluster(input.readInt(), input.readInt(), input.readInt(), input.readInt()))
                    } catch (e: IllegalArgumentException) {
                        throw CorruptSessionException("invalid unknown color cluster", e)
                    }
                }
            }
        } else emptyList()
        val quietZone = input.readBoolean()
        val version = input.readInt()
        requireCorrupt(version in 1..40, "invalid preprocessing version")
        val lockCorners = input.readBoolean()
        val lockVersion = input.readBoolean()
        val searchQuadNeighborhood = input.readBoolean()
        val trustKnownPayloadCount = input.readBoolean()
        val lockManualBits = input.readBoolean()
        val formatConstraint = if (hasFormatConstraint && input.readBoolean()) {
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
        val formatMismatchLimit = if (hasFormatMismatchLimit && input.readBoolean()) {
            input.readInt().also { requireCorrupt(it in 0..30, "invalid format-information error limit") }
        } else null
        val dataEccMismatchLimit = if (hasDataEccMismatchLimit && input.readBoolean()) {
            input.readInt().also { requireCorrupt(it in 0..MAX_QR_DATA_ECC_BITS, "invalid data/ECC error limit") }
        } else null
        val perspectiveQuad = if (hasPerspectiveCorrection && input.readBoolean()) {
            val points = List(4) { PointD(input.readDouble(), input.readDouble()) }
            ImageQuad(points[0], points[1], points[2], points[3])
        } else null
        val quad = if (input.readBoolean()) {
            val points = List(4) { PointD(input.readDouble(), input.readDouble()) }
            ImageQuad(points[0], points[1], points[2], points[3])
        } else null
        val logicalGridAssigned = if (hasLogicalGridAssignment) input.readBoolean() else true
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
        val priority = if (hasPrioritySearch) {
            val count = input.readInt()
            requireCorrupt(count in 0..4, "invalid priority-search count")
            buildSet {
                repeat(count) {
                    val coord = Coord(input.readInt(), input.readInt())
                    requireCorrupt(coord.x in 0 until dimension && coord.y in 0 until dimension, "priority-search coordinate outside matrix")
                    requireCorrupt(add(coord), "duplicate priority-search coordinate")
                }
            }
        } else emptySet()
        return try {
            PreprocessSettings(
                rotationDegrees = rotation,
                polarity = polarity,
                thresholdMode = thresholdMode,
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
                lockManualBits = lockManualBits,
                perspectiveCorrectionQuad = perspectiveQuad,
                manualQuad = quad,
                manualFinders = manualFinders,
                logicalGridAssigned = logicalGridAssigned,
                detectedFinders = candidates,
                confirmedFinderPolarity = confirmedPolarity,
                preRecoveryEdits = edits,
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
        require(settings.preRecoveryPrioritySearch.size <= 4)
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
        for (y in 0 until matrix.dimension) for (x in 0 until matrix.dimension) {
            val c = matrix[x, y]
            require(c.confidence.isFinite() && c.confidence in 0f..1f)
            out.writeByte(c.value.ordinal)
            out.writeByte(c.source.ordinal)
            out.writeFloat(c.confidence)
            out.writeBoolean(c.prioritySearch)
        }
    }

    private fun readMatrix(input: DataInputStream, hasPrioritySearch: Boolean): QrMatrix? {
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
            if (hasPrioritySearch && input.readBoolean()) {
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

    private fun gunzipBounded(compressed: ByteArray, expectedSize: Int): ByteArray = try {
        GZIPInputStream(ByteArrayInputStream(compressed)).use { input ->
            val raw = readBounded(input, expectedSize)
            requireCorrupt(raw.size == expectedSize, "uncompressed session size mismatch")
            raw
        }
    } catch (e: CorruptSessionException) {
        throw e
    } catch (e: Exception) {
        throw CorruptSessionException("GZIP payload is corrupt", e)
    }

    private fun readBounded(input: InputStream, maxBytes: Int): ByteArray {
        val out = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            if (total > maxBytes) throw CorruptSessionException("uncompressed session exceeds size limit")
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    private inline fun <reified T : Enum<T>> enumStrict(index: Int, label: String): T {
        val entries = enumValues<T>()
        if (index !in entries.indices) throw CorruptSessionException("invalid $label enum index $index")
        return entries[index]
    }

    private fun requireCorrupt(condition: Boolean, message: String) {
        if (!condition) throw CorruptSessionException(message)
    }

    fun isValidQrDimension(d: Int): Boolean = d in 21..177 && (d - 17) % 4 == 0

    fun isSafeSessionId(id: String): Boolean = id.length in 1..80 && id.all { it.isLetterOrDigit() || it == '-' || it == '_' }
}
