package evaluation

import com.google.gson.GsonBuilder
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import io.github.earthdiver.qrrepair.core.*
import java.io.File
import java.lang.management.ManagementFactory
import java.lang.management.MemoryType
import java.security.MessageDigest
import java.util.Random

/** Synthetic, matrix-first baseline. No changes to production search or external images. */
private val benchmarkJson = GsonBuilder().serializeNulls().create()
private val scenarioNames = listOf("clean", "byte-erasures", "partial-bits", "within-unique",
    "beyond-unique", "beyond-johnson", "mixed", "burst", "multiple-blocks", "ambiguous",
    "fixed-conflict", "memory-stop", "cancel")

private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
private fun wordsKey(words: IntArray) = words.joinToString(",")
private fun shuffled(values: List<Int>, random: Random) = values.toMutableList().also { java.util.Collections.shuffle(it, random) }

private const val IMAGE_SCALE = 6
private fun rowShift(y: Int, side: Int) = (3 * kotlin.math.sin(y * Math.PI / side)).toInt()

private class BenchmarkFixture(val name: String, val seed: Int) {
    val version = if (name in setOf("burst", "multiple-blocks")) 3 else 1
    val ecc = if (version == 3) ErrorCorrectionLevel.Q else ErrorCorrectionLevel.L
    val mask = seed % 8
    val text = "BASE ${seed.toString().padStart(3, '0')} A"
    private fun encode(text: String): QrMatrix {
        val qr = Encoder.encode(text, ecc, mapOf(EncodeHintType.QR_VERSION to version, EncodeHintType.QR_MASK_PATTERN to mask))
        return QrMatrix(qr.matrix.width).apply {
            for (y in 0 until dimension) for (x in 0 until dimension)
                set(x, y, if (qr.matrix.get(x, y).toInt() == 1) ModuleValue.ONE else ModuleValue.ZERO, 1f, ModuleSource.IMAGE)
        }
    }
    val matrix = encode(text)
    val extraction = CodewordExtractor.extract(matrix, version, mask)
    val original = extraction.codewords.map { it.value }.toIntArray()
    private val de = QrBlockInterleaver.deinterleave(extraction, version, ecc)
    val witnesses = mutableListOf(original)
    val mismatchLimit: Int? = if (name in setOf("ambiguous", "partial-bits", "byte-erasures")) 0 else null
    val workingBytes = if (name == "memory-stop") 0L else 32L * 1024 * 1024

    private fun erase(raw: Int, bits: Int = 8) {
        extraction.codewords[raw].coordinates.takeLast(bits).forEach { c ->
            matrix.set(c.x, c.y, ModuleValue.ERASED, 0f, ModuleSource.IMAGE)
        }
    }
    private fun flip(raw: Int, fixed: Boolean = false) {
        val c = extraction.codewords[raw].coordinates.last()
        matrix.set(c.x, c.y, if (matrix[c.x, c.y].value == ModuleValue.ONE) ModuleValue.ZERO else ModuleValue.ONE,
            1f, if (fixed) ModuleSource.USER else ModuleSource.IMAGE)
    }
    init {
        val random = Random(seed.toLong())
        val parity = de.blocks.indices.map { block -> shuffled(de.rawLocations.indices.filter { raw ->
            val loc = requireNotNull(de.rawLocations[raw])
            loc.blockIndex == block && loc.localIndex >= de.blocks[block].numDataCodewords
        }, random) }
        when (name) {
            "clean" -> Unit
            "byte-erasures" -> shuffled(original.indices.toList(), random).take(de.ecSymbolsPerBlock).forEach { erase(it) }
            "partial-bits" -> shuffled(original.indices.toList(), random).take(20).forEach { erase(it, 1) }
            "within-unique" -> parity.single().take(3).forEach { flip(it) }
            "beyond-unique", "memory-stop" -> parity.single().take(4).forEach { flip(it) }
            "beyond-johnson" -> parity.single().take(5).forEach { flip(it) }
            "multiple-blocks" -> parity.forEach { positions -> positions.take(10).forEach { flip(it) } }
            "mixed", "cancel" -> {
                (2..8).forEach { erase(it, 1) }
                (24..25).forEach { erase(it) }
                (19..21).forEach { flip(it) }
            }
            "burst" -> extraction.codewords.flatMap { it.coordinates }.filter {
                it.x in 12..18 && it.y in 12..18
            }.forEach { matrix.set(it.x, it.y, ModuleValue.ERASED, 0f, ModuleSource.IMAGE) }
            "ambiguous" -> {
                val other = CodewordExtractor.extract(encode(text.dropLast(1) + "B"), version, mask)
                witnesses += other.codewords.map { it.value }.toIntArray()
                extraction.codewords.forEachIndexed { raw, word -> word.coordinates.forEachIndexed { bit, c ->
                    if ((original[raw] xor witnesses.last()[raw]) and (1 shl (7 - bit)) != 0)
                        matrix.set(c.x, c.y, ModuleValue.ERASED, 0f, ModuleSource.IMAGE)
                } }
            }
            "fixed-conflict" -> {
                parity.single().take(4).forEachIndexed { index, raw -> flip(raw, index == 0) }
            }
            else -> error("Unknown scenario: $name")
        }
    }
    fun metadata(): Map<String, Any?> {
        val damaged = QrBlockInterleaver.deinterleave(CodewordExtractor.extract(matrix, version, mask), version, ecc)
        return mapOf("scenario" to name, "seed" to seed, "version" to version, "ecc" to ecc.name, "mask" to mask,
            "inputHash" to digest((0 until matrix.dimension).joinToString(";") { y ->
                (0 until matrix.dimension).joinToString(",") { x -> "${matrix[x,y].value}:${matrix[x,y].source}" }
            }), "originalCodewords" to original, "knownValidWitnesses" to witnesses,
            "originalAllowedByFixedCells" to (name != "fixed-conflict"), "mismatchLimit" to mismatchLimit,
            "workingLimitBytes" to workingBytes, "blocks" to damaged.blocks.mapIndexed { index, b ->
                val truth = de.blocks[index].codewords
                val erasures = b.unknownMasks.count { it != 0 }
                val errors = truth.indices.count { b.unknownMasks[it] == 0 && b.codewords[it] != truth[it] }
                val n = b.codewords.size - erasures
                val k = b.numDataCodewords
                val radius = if (n >= k) n - kotlin.math.sqrt(n.toDouble() * (k - 1)) else null
                mapOf("n" to b.codewords.size, "k" to k, "ecSymbols" to de.ecSymbolsPerBlock,
                    "erasedSymbols" to erasures, "fullyKnownSymbolErrors" to errors,
                    "unknownBits" to b.unknownMasks.sumOf { it.countOneBits() },
                    "knownBitErrors" to truth.indices.sumOf { ((truth[it] xor b.codewords[it]) and b.unknownMasks[it].inv() and 255).countOneBits() },
                    "exactSpaceDimension" to RsBitConstraintSolver().analyze(b, de.ecSymbolsPerBlock)?.dimension,
                    "puncturedJohnsonRadiusExclusive" to radius,
                    "hardDecisionRegion" to when {
                        n < k -> "too-many-erasures"
                        2 * errors + erasures <= de.ecSymbolsPerBlock -> "within-unique"
                        radius != null && errors < radius -> "beyond-unique-within-johnson"
                        else -> "at-or-beyond-johnson"
                    })
            })
    }
    fun raster(damage: String): LumaImage {
        val scale = IMAGE_SCALE
        val side = (matrix.dimension + 8) * scale
        val formatCells = FormatSolver.copies(matrix.dimension).flatten().toSet()
        fun luminance(px: Int, py: Int): Int {
            val x = Math.floorDiv(px, scale) - 4
            val y = Math.floorDiv(py, scale) - 4
            if (x !in 0 until matrix.dimension || y !in 0 until matrix.dimension) return 245
            val black = matrix[x, y].value == ModuleValue.ONE
            return when {
                damage == "format" && (x to y) in formatCells -> 128
                matrix[x, y].value == ModuleValue.ERASED -> 128
                black -> 15
                else -> 245
            }
        }
        return LumaImage(side, side, IntArray(side * side) { i ->
            val x = i % side
            val y = i / side
            val base = luminance(x, y)
            when (damage) {
                "shadow" -> base - base * x / (2 * side)
                "glare" -> if (kotlin.math.abs(x - y - seed % scale) < scale) 235 else base
                "warp" -> luminance(x - rowShift(y, side), y)
                "blur" -> (-3..3).sumOf { dx -> (-3..3).sumOf { dy -> luminance(x + dx, y + dy) } } / 49
                else -> base
            }
        })
    }

    fun knownGrid(settings: PreprocessSettings): PreprocessSettings {
        val last = matrix.dimension - 1
        fun center(x: Int, y: Int) = PointD((4 + x) * IMAGE_SCALE + (IMAGE_SCALE - 1) / 2.0,
            (4 + y) * IMAGE_SCALE + (IMAGE_SCALE - 1) / 2.0)
        return settings.copy(version = version, logicalGridAssigned = true,
            manualQuad = ImageQuad(center(0, 0), center(last, 0), center(last, last), center(0, last)),
            manualFinders = mapOf(FinderRole.TOP_LEFT to center(3, 3), FinderRole.TOP_RIGHT to center(last - 3, 3),
                FinderRole.BOTTOM_LEFT to center(3, last - 3)))
    }

    /** Geometry and sampling are timed separately; sampled values never replace the input constraints. */
    fun imageProbe(): Map<String, Any?> {
        val scale = 6
        val side = (matrix.dimension + 8) * scale
        val image = LumaImage(side, side, IntArray(side * side) { offset ->
            val x = offset % side / scale - 4
            val y = offset / side / scale - 4
            if (x !in 0 until matrix.dimension || y !in 0 until matrix.dimension) 255 else when (matrix[x,y].value) {
                ModuleValue.ONE -> 0
                ModuleValue.ZERO -> 255
                else -> 127
            }
        })
        var start = System.nanoTime()
        val boundaries = desktopBoundaries(image)
        val detectionMs = (System.nanoTime() - start) / 1e6
        start = System.nanoTime()
        val estimated = InitialGridEstimator.estimate(image, boundaries)
        val estimateMs = (System.nanoTime() - start) / 1e6
        val geometry = GridGeometry(version, FinderRole.TOP_LEFT, 45.0, 45.0, scale.toDouble(), 0.0)
        start = System.nanoTime()
        val sampled = MatrixSampler(image).sample(geometry).first
        val samplingMs = (System.nanoTime() - start) / 1e6
        val differences = (0 until matrix.dimension).sumOf { y -> (0 until matrix.dimension).count { x ->
            matrix[x,y].value != sampled[x,y].value
        } }
        return mapOf("boundaryDetectionMs" to detectionMs, "boundaryCount" to boundaries.size, "gridEstimateMs" to estimateMs, "estimatedVersion" to estimated?.version,
            "knownGeometrySamplingMs" to samplingMs, "sampledValueDifferences" to differences)
    }
}

private fun evidence(candidate: RecoveryCandidate): Map<String, Any?> = mapOf(
    "codewords" to candidate.correctedInterleaved, "text" to candidate.text,
    "dataBytes" to candidate.dataBytes.map { it.toInt() and 255 },
    "knownBitMismatches" to candidate.knownBitMismatchesAfterCorrection,
    "methods" to candidate.actualBlockMethods, "repairInfo" to candidate.repairInfo,
    "blockDerivations" to candidate.blockDerivations, "combinationSearch" to candidate.combinationSearch,
    "derivationsOmitted" to candidate.derivationsOmitted,
    "alternatives" to candidate.alternativeDerivations.map(::evidence))

private fun runBenchmark(f: BenchmarkFixture, checkLimit: Int, guardMillis: Long, profileEvent: DecoderInvocation? = null): Map<String, Any?> {
    val pools = ManagementFactory.getMemoryPoolMXBeans().filter { it.type == MemoryType.HEAP }
    pools.forEach { it.resetPeakUsage() }
    val gc = ManagementFactory.getGarbageCollectorMXBeans()
    val gcCount = gc.sumOf { it.collectionCount.coerceAtLeast(0) }
    val gcTime = gc.sumOf { it.collectionTime.coerceAtLeast(0) }
    val heapBefore = ManagementFactory.getMemoryMXBean().heapMemoryUsage.used
    var checks = 0
    var requestedAt: Long? = null
    var stopCause: String? = null
    val limit = if (f.name == "cancel") minOf(checkLimit, 1000) else checkLimit
    val allocation = (ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean)
        ?.takeIf { it.isThreadAllocatedMemorySupported && it.isThreadAllocatedMemoryEnabled }
    val threadId = Thread.currentThread().id
    val allocatedBefore = allocation?.getThreadAllocatedBytes(threadId)
    val start = System.nanoTime()
    profileEvent?.begin()
    val outcome = try { RecoveryEngine().recoverWithDiagnostics(f.matrix,
        formatConstraint = QrFormatConstraint(f.ecc, f.mask), maxKnownDataEccMismatches = f.mismatchLimit,
        partialBitSearchLimit = 12, listDecodingAdditionalErrors = 1,
        retentionBudget = RecoveryRetentionBudget(workingLimitBytes = f.workingBytes),
        // Fixed logical budget: wall-clock list slices must not change the searched input set.
        listDecodingBudget = ListDecodingBudget(clockMillis = { 0L }),
        stopRequested = {
            checks++
            val now = System.nanoTime()
            if (requestedAt == null) {
                stopCause = when {
                    checks > limit -> "check-limit"
                    (now - start) / 1_000_000 >= guardMillis -> "wall-guard"
                    else -> null
                }
                if (stopCause != null) requestedAt = now
            }
            requestedAt != null
        }) } finally { profileEvent?.end(); profileEvent?.commit() }
    val finished = System.nanoTime()
    val allocatedBytes = allocatedBefore?.let { allocation.getThreadAllocatedBytes(threadId) - it }
    // Read counters before candidate serialization allocates memory.
    val peaks = pools.associate { it.name to it.peakUsage.used }
    val gcCountDelta = gc.sumOf { it.collectionCount.coerceAtLeast(0) } - gcCount
    val gcTimeDelta = gc.sumOf { it.collectionTime.coerceAtLeast(0) } - gcTime
    val keys = outcome.candidates.map { wordsKey(it.correctedInterleaved) }.toSet()
    val found = wordsKey(f.original) in keys
    val candidates = outcome.candidates.sortedBy { wordsKey(it.correctedInterleaved) }.map(::evidence)
    return mapOf("decodeMs" to (finished - start) / 1e6, "checks" to checks, "checkLimit" to limit,
        "stopCause" to stopCause, "cancellationReturnMs" to requestedAt?.let { (finished - it) / 1e6 },
        "decoderThreadAllocatedBytes" to allocatedBytes, "heapBeforeBytes" to heapBefore, "heapPoolPeakBytes" to peaks,
        "heapPoolPeakSumBytes" to peaks.values.sum(), "gcCount" to gcCountDelta, "gcMillis" to gcTimeDelta,
        "originalFound" to found, "retainedCandidateCount" to keys.size,
        "otherThanOriginalCount" to keys.count { it != wordsKey(f.original) },
        "wrongSingletonRelativeToOriginal" to (keys.size == 1 && !found),
        "knownValidWitnessesFound" to f.witnesses.count { wordsKey(it) in keys },
        "candidateSetHash" to digest(keys.sorted().joinToString(";")),
        "evidenceHash" to digest(benchmarkJson.toJson(candidates)),
        "candidates" to candidates, "completeness" to outcome.searchDiagnostic.completeness,
        "searchDiagnostic" to outcome.searchDiagnostic, "failureStage" to outcome.failure?.stage,
        "formatAttempts" to outcome.formatAttempts)
}

private fun benchmarkSelfTest() {
    recoveryProfileSelfTest()
    for (name in scenarioNames) {
        val f = BenchmarkFixture(name, 0)
        check(benchmarkJson.toJson(f.metadata()) == benchmarkJson.toJson(BenchmarkFixture(name, 0).metadata()))
        if (name == "ambiguous") {
            check(!f.witnesses[0].contentEquals(f.witnesses[1]))
            val observed = CodewordExtractor.extract(f.matrix, f.version, f.mask)
            for (witness in f.witnesses) observed.codewords.forEachIndexed { index, word ->
                check(((witness[index] xor word.value) and word.unknownBits.inv() and 255) == 0)
            }
        }
    }
    val clean = runBenchmark(BenchmarkFixture("clean", 0), 100_000, 60_000)
    check(clean["originalFound"] == true)
    check(clean["wrongSingletonRelativeToOriginal"] == false)
    val ambiguous = runBenchmark(BenchmarkFixture("ambiguous", 0), 100_000, 60_000)
    check(ambiguous["knownValidWitnessesFound"] == 2)
    val cancelled = runBenchmark(BenchmarkFixture("cancel", 0), 100_000, 60_000)
    check(cancelled["stopCause"] == "check-limit")
    check(cancelled["completeness"] == SearchCompleteness.UNSEARCHED)
    check(cancelled["cancellationReturnMs"] != null)
    val fixed = runBenchmark(BenchmarkFixture("fixed-conflict", 0), 100_000, 60_000)
    check(fixed["originalFound"] == false)
    val memory = runBenchmark(BenchmarkFixture("memory-stop", 0), 100_000, 60_000)
    check(memory["completeness"] == SearchCompleteness.UNSEARCHED)
    println("Recovery benchmark fixture and measurement checks passed")
}

private fun imageBenchmark(args: Array<String>) {
    require(args.size == 5) { "Use an image mode followed by OUTPUT SEEDS CHECKS GUARD_MS" }
    val output = File(args[1])
    val seeds = args[2].toInt()
    val limit = args[3].toInt()
    val guard = args[4].toLong()
    require(seeds in 1..100 && limit > 0 && guard > 0)
    require(output.createNewFile()) { "Choose a new output file" }
    output.bufferedWriter().use { writer ->
        for (seed in 0 until seeds) for (damage in listOf("clean", "shadow", "glare", "warp", "blur", "format", "mixed", "ambiguous")) {
            val fixture = BenchmarkFixture(if (damage in setOf("mixed", "ambiguous")) damage else "clean", seed)
            val pixels = fixture.raster(damage)
            val oriented = args[0] == "--oriented-images"
            val knownGrid = args[0] == "--known-grid-images"
            // The sinusoidal row displacement cannot be represented by a projective quad.
            val image = if (knownGrid && damage == "warp") LumaImage(pixels.width, pixels.height,
                IntArray(pixels.pixels.size) { i ->
                    val y = i / pixels.width
                    val x = i % pixels.width + rowShift(y, pixels.height)
                    if (x < pixels.width) pixels.pixels[y * pixels.width + x] else 245
                }) else pixels
            val pools = ManagementFactory.getMemoryPoolMXBeans().filter { it.type == MemoryType.HEAP }
            pools.forEach { it.resetPeakUsage() }
            val start = System.nanoTime()
            fun millis() = (System.nanoTime() - start) / 1e6
            val estimate = if (knownGrid) null else InitialGridEstimator.estimate(image, desktopBoundaries(image))
            var checks = 0
            var stop: String? = null
            val result = if (!knownGrid && estimate == null) null else run {
                val rgb = IntArray(image.pixels.size) { i -> -0x1000000 or (image.pixels[i] * 0x10101) }
                val thresholds = InitialThresholdEstimator.estimate(android.graphics.Bitmap(image.width, image.height, rgb))
                val base = PreprocessSettings(blackMax = thresholds?.blackMax ?: 96,
                    whiteMin = thresholds?.whiteMin ?: 160, automaticThresholds = true).withInitialErrorLimits()
                val settings = when {
                    knownGrid -> fixture.knownGrid(base)
                    oriented -> {
                        // The generator prints upright symbols. Reorder only the measured coordinates.
                        val corners = requireNotNull(estimate).moduleCenterQuad.asList().sortedBy { it.y }
                        val top = corners.take(2).sortedBy { it.x }
                        val bottom = corners.drop(2).sortedBy { it.x }
                        base.withInitialGrid(estimate).copy(logicalGridAssigned = true,
                            manualQuad = ImageQuad(top[0], top[1], bottom[1], bottom[0]))
                    }
                    else -> base.withInitialGrid(estimate)
                }
                AutoRecoveryEngine().run(image, profile = SamplingProfile(settings, estimate?.confirmedPolarity == FinderPolarity.INVERTED),
                    listDecodingBudget = ListDecodingBudget(clockMillis = { 0L }), stopRequested = {
                        checks++
                        if (stop == null) stop = when {
                            checks > limit -> "checks"
                            millis() >= guard -> "wall"
                            else -> null
                        }
                        stop != null
                    })
            }
            val elapsed = millis()
            val mixedTruth = result?.bestAttempt?.matrix?.takeIf { damage == "mixed" && it.dimension == fixture.matrix.dimension }?.let { matrix ->
                val words = CodewordExtractor.extract(matrix, fixture.version, fixture.mask).codewords
                val mismatches = words.mapIndexed { i, word ->
                    ((word.value xor fixture.original[i]) and word.unknownBits.inv() and 255).countOneBits()
                }
                mapOf("knownBitErrors" to mismatches.sum(), "erasedWords" to words.count { it.unknownBits != 0 },
                    "wrongCompleteWords" to words.indices.count { words[it].unknownBits == 0 && mismatches[it] > 0 },
                    "allowedBitErrors" to ErrorLimitPolicy.data(fixture.version, ErrorLimitMode.AUTOMATIC, null))
            }
            val candidates = result?.entries.orEmpty().map { it.candidate }
            val rank = candidates.indexOfFirst { it.version == fixture.version && it.ecLevel == fixture.ecc &&
                it.dataMask == fixture.mask && it.correctedInterleaved.contentEquals(fixture.original) }
            writer.appendLine(benchmarkJson.toJson(mapOf("mixedTruth" to mixedTruth, "estimatedVersion" to estimate?.version,
                "gridMode" to when { knownGrid -> "known"; oriented -> "oriented"; else -> "auto" }, "damage" to damage, "seed" to seed, "rank" to
                (rank + 1).takeIf { rank >= 0 }, "contentFound" to candidates.any { it.text == fixture.text },
                "candidateCount" to candidates.size, "knownWitnessesFound" to fixture.witnesses.count { witness ->
                    candidates.any { it.version == fixture.version && it.ecLevel == fixture.ecc && it.dataMask == fixture.mask &&
                        it.correctedInterleaved.contentEquals(witness) } },
                "heapPoolPeakSumBytes" to pools.sumOf { it.peakUsage.used }, "noGeometry" to (!knownGrid && estimate == null),
                "finderRolesAssigned" to if (knownGrid) 3 else estimate?.autoAssignedFinders.orEmpty().size,
                "attemptedGeometries" to (result?.attemptedGeometries ?: 0), "elapsedMs" to elapsed,
                "checks" to checks, "checkLimit" to limit, "guardMillis" to guard, "stop" to stop,
                "diagnostic" to result?.searchDiagnostic)))
            writer.flush()
        }
    }
}

fun main(args: Array<String>) {
    if (args.firstOrNull() in setOf("--images", "--known-grid-images", "--oriented-images")) { imageBenchmark(args); return }
    if (args.contentEquals(arrayOf("--self-test"))) { benchmarkSelfTest(); return }
    require(args.size % 2 == 0) { "Use --output DIR [--samples N] [--warmups N] [--seeds N] [--checks N] [--guard-ms N] [--case NAME] [--profile true|false]" }
    val options = args.toList().chunked(2).associate { it[0] to it[1] }
    require(options.keys.all { it in setOf("--output", "--samples", "--warmups", "--seeds", "--checks", "--guard-ms", "--case", "--profile") })
    val output = File(requireNotNull(options["--output"]))
    require(!output.exists()) { "Output directory already exists; choose a fresh directory" }
    val samples = options["--samples"]?.toInt() ?: 20
    val warmups = options["--warmups"]?.toInt() ?: 5
    val seeds = options["--seeds"]?.toInt() ?: 3
    val checks = options["--checks"]?.toInt() ?: 100_000
    val guard = options["--guard-ms"]?.toLong() ?: 10_000L
    require(samples > 0 && warmups >= 0 && seeds in 1..100 && checks > 0 && guard > 0)
    val profileEnabled = options["--profile"]?.toBooleanStrict() ?: false
    val selected = options["--case"]?.let { require(it in scenarioNames); listOf(it) } ?: scenarioNames
    check(output.mkdirs())
    File(output, "environment.json").writeText(benchmarkJson.toJson(mapOf(
        "schemaVersion" to 1, "jfrEnabled" to profileEnabled,
        "sourceRevision" to ProcessBuilder("git", "rev-parse", "HEAD").start().inputStream.bufferedReader().readText().trim(),
        "sourceDirty" to ProcessBuilder("git", "status", "--porcelain").start().inputStream.bufferedReader().readText().isNotBlank(),
        "sourceFingerprint" to digest(listOf(File("../../shared/src/commonMain"),
            File("../../androidApp/src/main/java/io/github/earthdiver/qrrepair/core"), File("src/main"))
            .flatMap { it.walkTopDown().filter(File::isFile).toList() }.sortedBy { it.path }
            .joinToString("\n") { it.path + ":" + digest(it.readText()) }), "javaVersion" to System.getProperty("java.version"),
        "vm" to System.getProperty("java.vm.name"), "os" to System.getProperty("os.name"),
        "osVersion" to System.getProperty("os.version"), "arch" to System.getProperty("os.arch"), "processors" to Runtime.getRuntime().availableProcessors(),
        "maxHeapBytes" to Runtime.getRuntime().maxMemory(), "samples" to samples, "warmups" to warmups,
        "seedCount" to seeds, "checkLimit" to checks, "wallGuardMillis" to guard,
        "listClock" to "frozen; logical check budget, not production time slices",
        "memoryMetric" to "sum of JVM heap pool peaks, not simultaneous heap peak or process RSS",
        "firstCorrectCandidateMs" to "unavailable: production API returns candidates at invocation completion",
        "imageProbe" to "synthetic unrotated raster; ZXing Java boundary detection, grid validation and known-geometry sampling; separate from recovery")))
    (if (profileEnabled) RecoveryProfile(output) else null).use { profile ->
        File(output, "runs.jsonl").bufferedWriter().use { writer ->
            for (name in selected) for (seed in 0 until seeds) {
                val fixture = BenchmarkFixture(name, seed)
                val metadata = fixture.metadata()
                repeat(warmups) { runBenchmark(fixture, checks, guard); fixture.imageProbe() }
                repeat(samples) { iteration ->
                    // Image probing and fixture generation are outside the decoder timing and heap peaks.
                    val image = fixture.imageProbe()
                    val event = if (profile != null) DecoderInvocation().apply {
                        scenario = name; this.seed = seed; this.iteration = iteration
                    } else null
                    val result = runBenchmark(fixture, checks, guard, event)
                    writer.appendLine(benchmarkJson.toJson(mapOf("fixture" to metadata,
                        "iteration" to iteration, "image" to image, "result" to result)))
                    writer.flush()
                    println("$name seed=$seed sample=$iteration ms=${result["decodeMs"]} correct=${result["originalFound"]} ${result["completeness"]}")
                }
            }
        }
    }
}
