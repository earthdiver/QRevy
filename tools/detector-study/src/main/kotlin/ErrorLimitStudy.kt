package study

import com.google.gson.Gson
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.decoder.Version
import com.google.zxing.qrcode.encoder.Encoder
import io.github.earthdiver.qrrepair.core.*
import java.io.File
import kotlin.math.ceil
import kotlin.math.max
import kotlin.random.Random

/** Synthetic matrix study; never accepts source images or changes production defaults. */
private data class LimitFixture(
    val id: String, val version: Int, val ecc: String, val seed: Int, val family: String,
    val severity: Double, val matrix: QrMatrix, val truth: IntArray?, val text: String?,
    val mask: Int, val errors: Int, val erased: Int, val trustCount: Boolean = false,
)

private fun limitFixtures(versions: List<Int>, seeds: List<Int>, randomized: Boolean = false, sparseMaxVersion: Int = 5): Sequence<LimitFixture> = sequence {
    for (seed in seeds) for (v in versions) for (ec in ErrorCorrectionLevel.entries) {
        val meta = Version.getVersionForNumber(v)
        val dataWords = meta.totalCodewords - meta.getECBlocksForLevel(ec).totalECCodewords
        val random = Random(seed * 10000 + v * 10 + ec.ordinal)
        val text = buildString { repeat(max(1, dataWords * 55 / 100 - 4)) { append(('a'.code + random.nextInt(26)).toChar()) } }
        val qr = Encoder.encode(text, ec, mapOf(EncodeHintType.QR_VERSION to v))
        val original = QrMatrix(qr.matrix.width).apply {
            for (y in 0 until dimension) for (x in 0 until dimension)
                set(x, y, if (qr.matrix.get(x, y).toInt() == 1) ModuleValue.ONE else ModuleValue.ZERO, 1f, ModuleSource.IMAGE)
        }
        val extraction = CodewordExtractor.extract(original, v, qr.maskPattern)
        val truth = extraction.codewords.map { it.value }.toIntArray()
        val coords = extraction.codewords.flatMap { it.coordinates }
        val bitOrder = coords.shuffled(random)
        val wordOrder = extraction.codewords.shuffled(random).flatMap { it.coordinates.shuffled(random) }
        val center = coords[random.nextInt(coords.size)]
        val patchOrder = coords.sortedBy { (it.x - center.x) * (it.x - center.x) + (it.y - center.y) * (it.y - center.y) }
        val profiles = buildList {
            if (randomized) {
                repeat(12) {
                    add("dispersed" to (.05 + random.nextDouble() * 4.95))
                    add("packed" to (.05 + random.nextDouble() * 19.95))
                    add("patch" to (.05 + random.nextDouble() * 19.95))
                }
                if (v <= sparseMaxVersion) repeat(4) {
                    for (fraction in listOf(50, 65, 75, 85)) add("sparse-$fraction" to (.05 + random.nextDouble() * 2.95))
                    add("unknown-format" to (.05 + random.nextDouble() * 2.95))
                }
                return@buildList
            }
            add("clean" to 0.0)
            for (r in listOf(.25, .5, 1.0, 2.0, 3.0, 5.0)) add("dispersed" to r)
            for (r in listOf(1.0, 3.0, 5.0, 10.0, 20.0)) { add("packed" to r); add("patch" to r) }
            add("mixed" to .5); add("mixed" to 2.0)
            if (v <= sparseMaxVersion) {
                for (r in listOf(.25, 1.0, 2.0)) {
                    for (fraction in listOf(50, 65, 75, 85)) add("sparse-$fraction" to r)
                    add("unknown-format" to r)
                }
                add("noise" to 50.0)
            }
        }
        for ((family, severity) in profiles) {
            val matrix = original.copy()
            fun erase(c: Coord) { matrix.set(c.x, c.y, ModuleValue.ERASED, 0f, ModuleSource.IMAGE) }
            if (family == "mixed") wordOrder.take((coords.size / 8 / 10) * 8).forEach(::erase)
            if (family.startsWith("sparse-")) bitOrder.take(coords.size * family.substringAfter('-').toInt() / 100).forEach(::erase)
            if (family == "unknown-format") studyFormatCoordinates(matrix.dimension).forEach { (x, y) -> erase(Coord(x, y)) }
            val order = when (family) { "packed" -> wordOrder; "patch" -> patchOrder; else -> bitOrder }
            val count = ceil(coords.size * severity / 100).toInt()
            order.filter { matrix[it.x, it.y].value != ModuleValue.ERASED }.take(count).forEach { c ->
                val old = matrix[c.x, c.y].value
                matrix.set(c.x, c.y, if (old == ModuleValue.ONE) ModuleValue.ZERO else ModuleValue.ONE, 1f, ModuleSource.IMAGE)
            }
            val erased = coords.count { matrix[it.x, it.y].value == ModuleValue.ERASED }
            val errors = coords.count { matrix[it.x, it.y].value != ModuleValue.ERASED && matrix[it.x, it.y].value != original[it.x, it.y].value }
            yield(LimitFixture("s${seed}-v$v-$ec-$family-$severity", v, ec.name, seed, family, severity,
                matrix, if (family == "noise") null else truth, if (family == "noise") null else text, qr.maskPattern, errors, erased))
        }
    }
    // Deliberately ambiguous observations: the threshold cannot distinguish these payloads.
    for (seed in seeds) {
        val random = Random(seed)
        val text = buildString { repeat(13) { append(('a'.code + random.nextInt(26)).toChar()) } }
        val qr = Encoder.encode(text, ErrorCorrectionLevel.L, mapOf(EncodeHintType.QR_VERSION to 1))
        val matrix = QrMatrix(21).apply {
            for (y in 0 until 21) for (x in 0 until 21) set(x, y,
                if (qr.matrix.get(x, y).toInt() == 1) ModuleValue.ONE else ModuleValue.ZERO, 1f, ModuleSource.IMAGE)
        }
        val ex = CodewordExtractor.extract(matrix, 1, qr.maskPattern)
        val truth = ex.codewords.map { it.value }.toIntArray()
        val erased = ex.codewords.drop(19).flatMap { it.coordinates } + ex.codewords[5].coordinates.takeLast(4)
        erased.forEach { matrix.set(it.x, it.y, ModuleValue.ERASED, 0f, ModuleSource.IMAGE) }
        yield(LimitFixture("s$seed-ambiguous", 1, "L", seed, "ambiguous", 0.0, matrix, truth, text, qr.maskPattern, 0, erased.size))
    }
}

/** Run with --output FILE --versions 1,2,5,10,20,40 --seeds 17,53 --limits unlimited,1,2,3,5.
 * Every limit gets an independent production recovery call, not a post-filtered candidate list.
 * --ids FILE replays exactly the newline-delimited fixture ids in that file.
 */
fun main(args: Array<String>) {
    require(args.size % 2 == 0)
    val options = args.toList().chunked(2).associate { it[0] to it[1] }
    require(options.keys.all { it in setOf("--versions", "--seeds", "--limits", "--checks", "--timeout-ms", "--ids", "--output", "--randomized", "--sparse-max-version") }) { "Unknown option" }
    val versions = (options["--versions"] ?: "1,2,5,10,20,40").split(',').map(String::toInt)
    val seeds = (options["--seeds"] ?: "17,53").split(',').map(String::toInt)
    val limits = (options["--limits"] ?: "unlimited").split(',')
    require(versions.isNotEmpty() && versions.all { it in 1..40 })
    require(limits.isNotEmpty() && limits.distinct().size == limits.size)
    require(limits.all { it in setOf("unlimited", "one-bit") || it.toDoubleOrNull()?.let { p -> p.isFinite() && p > 0 && p <= 100 } == true })
    val checkBudget = (options["--checks"] ?: "200000").toInt()
    val timeBudgetMs = (options["--timeout-ms"] ?: "3000").toLong()
    require(checkBudget > 0 && timeBudgetMs > 0)
    val sparseMaxVersion = (options["--sparse-max-version"] ?: "5").toInt()
    require(sparseMaxVersion in 1..40)
    require(options["--randomized"] in listOf(null, "true", "false"))
    val ids = options["--ids"]?.let { File(it).readLines().toSet() }
    val randomized = options["--randomized"] == "true"
    val output = File(requireNotNull(options["--output"]))
    output.parentFile?.mkdirs()
    val gson = Gson()
    var runCount = 0
    // Untimed warmup uses independent synthetic input and never receives the evaluated truth.
    val warm = limitFixtures(listOf(1), listOf(9001)).first()
    repeat(8) { RecoveryEngine().recover(warm.matrix) }
    output.bufferedWriter().use { writer ->
        limitFixtures(versions, seeds, randomized, sparseMaxVersion).filter { ids == null || it.id in ids }.forEach { f ->
            val totalBits = Version.getVersionForNumber(f.version).totalCodewords * 8
            // Rotate order deterministically to spread JIT/GC/order effects across policies.
            val offset = (f.id.hashCode().toLong().let { if (it < 0) -it else it } % limits.size).toInt()
            val order = limits.drop(offset) + limits.take(offset)
            for (policy in order) {
                val limit = when (policy) { "unlimited" -> null; "one-bit" -> 1; else -> max(1, ceil(totalBits * policy.toDouble() / 100).toInt()) }
                val engine = RecoveryEngine()
                var checks = 0
                var stopped: String? = null
                val start = System.nanoTime()
                val outcome = try {
                    engine.recoverWithDiagnostics(f.matrix.copy(), maxKnownDataEccMismatches = limit,
                        trustKnownPayloadCount = f.trustCount,
                        cancellation = RecoveryCancellation {
                            checks++
                            when {
                                checks > checkBudget -> { stopped = "check_budget"; true }
                                (checks == 1 || checks % 128 == 0) && (System.nanoTime() - start) / 1_000_000 > timeBudgetMs -> { stopped = "wall_budget"; true }
                                else -> false
                            }
                        })
                } catch (_: RecoveryCancelledException) { null }
                val results = outcome?.candidates.orEmpty().sortedWith(recoveryCandidateComparator)
                val ms = (System.nanoTime() - start) / 1e6
                val correct = results.filter { f.truth != null && it.dataMask == f.mask && it.ecLevel.name == f.ecc && it.correctedInterleaved.contentEquals(f.truth) }
                check(correct.all { it.text == f.text }) { "Ground-truth raw codewords and payload disagree: ${f.id}" }
                check(results.all { limit == null || it.knownBitMismatchesAfterCorrection <= limit })
                if (f.truth != null) check(correct.all { it.knownBitMismatchesAfterCorrection == f.errors })
                val row = linkedMapOf<String, Any?>(
                    "id" to f.id, "version" to f.version, "ecc" to f.ecc, "seed" to f.seed,
                    "family" to f.family, "severity_percent" to f.severity, "policy_percent" to policy,
                    "total_bits" to totalBits, "known_bits" to totalBits - f.erased,
                    "true_errors" to f.errors, "has_truth" to (f.truth != null), "limit" to limit,
                    "correct_raw" to correct.isNotEmpty(), "correct_text" to results.any { f.text != null && it.text == f.text },
                    "correct_rank" to results.indexOfFirst { it in correct }.takeIf { it >= 0 },
                    "parsed_candidates" to results.count { it.text != null },
                    "other_payloads" to results.filter { it.text != null && it.text != f.text }.map { it.text }.distinct().size,
                    "other_zero_mismatch_payloads" to results.filter { it.text != null && it.text != f.text && it.knownBitMismatchesAfterCorrection == 0 }.map { it.text }.distinct().size,
                    "candidate_mismatches" to results.map { it.knownBitMismatchesAfterCorrection },
                    "other_raw_candidates" to results.count { it.text != null && it !in correct },
                    "other_payload_mismatches" to results.filter { it.text != null && it.text != f.text }.map { it.knownBitMismatchesAfterCorrection },
                    "elapsed_ms" to ms, "checks" to checks, "stopped" to stopped,
                    "diagnostic" to outcome?.searchDiagnostic, "format_attempts" to outcome?.formatAttempts)
                writer.appendLine(gson.toJson(row)); writer.flush()
                runCount++
                if (runCount % 500 == 0) println("runs=$runCount last=${f.id} policy=$policy ms=${ms.toInt()} stopped=$stopped")
            }
        }
    }
    println("Completed $runCount recovery calls: ${output.name}")
}

private fun studyFormatCoordinates(d: Int): List<Pair<Int, Int>> = buildList {
    addAll(listOf(0 to 8, 1 to 8, 2 to 8, 3 to 8, 4 to 8, 5 to 8, 7 to 8, 8 to 8,
        8 to 7, 8 to 5, 8 to 4, 8 to 3, 8 to 2, 8 to 1, 8 to 0))
    for (y in d - 1 downTo d - 7) add(8 to y)
    for (x in d - 8 until d) add(x to 8)
}
