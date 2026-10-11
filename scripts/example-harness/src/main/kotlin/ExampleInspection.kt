package evaluation

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import io.github.earthdiver.qrrepair.core.*
import java.io.File
import javax.imageio.ImageIO

/** Sample an image at confirmed module centers without running recovery or supplying a payload. */
fun inspectExample(input: File, output: File) {
    val request = JsonParser.parseString(input.readText()).asJsonObject
    val bitmap = ImageIO.read(File(request["input"].asString)) ?: error("Unsupported image")
    val rgb = bitmap.getRGB(0, 0, bitmap.width, bitmap.height, null, 0, bitmap.width)
    val image = LumaImage(bitmap.width, bitmap.height, IntArray(rgb.size) {
        val p = rgb[it]
        (299 * ((p ushr 16) and 255) + 587 * ((p ushr 8) and 255) + 114 * (p and 255) + 500) / 1000
    })
    val corners = request.getAsJsonArray("quad").map {
        PointD(it.asJsonArray[0].asDouble, it.asJsonArray[1].asDouble)
    }
    val version = request["version"].asInt
    val quad = ImageQuad(corners[0], corners[1], corners[2], corners[3])
    val pitch = corners[0].distanceTo(corners[1]) / (16 + 4 * version)
    val geometry = GridGeometry(version, FinderRole.TOP_LEFT, 0.0, 0.0, pitch, 0.0,
        quad, GeometryModel.MANUAL_PROJECTIVE).reanchored()
    val initial = InitialThresholdEstimator.estimate(android.graphics.Bitmap(image.width, image.height, rgb))
    val settings = PreprocessSettings(version = version, manualQuad = quad, automaticThresholds = true,
        blackMax = initial?.blackMax ?: 96, whiteMin = initial?.whiteMin ?: 160).withInitialErrorLimits()
    val (matrix, stats) = MatrixSampler(image, SamplingProfile(settings, false)).sample(geometry)
    val rows = (0 until matrix.dimension).map { y ->
        (0 until matrix.dimension).joinToString("") { x ->
            when (matrix[x, y].value) { ModuleValue.ONE -> "1"; ModuleValue.ZERO -> "0"; else -> "?" }
        }
    }
    output.parentFile.mkdirs()
    output.writeText(GsonBuilder().setPrettyPrinting().create().toJson(mapOf(
        "rows" to rows, "blackMax" to stats.thresholds?.blackMax, "whiteMin" to stats.thresholds?.whiteMin,
        "provisionalBlackMax" to settings.provisionalBlackMax,
        "provisionalWhiteMin" to settings.provisionalWhiteMin)) + "\n")
}

/** Compare conventional RS and bounded bit assignments for every priority header assignment. */
fun conventionalExample(input: File, output: File) {
    val request = JsonParser.parseString(input.readText()).asJsonObject
    val parsed = QrTextMatrixParser.parse(File(request["input"].asString).readText())
    val priority = parsed.matrix.prioritySearchCoordinates()
    require(priority.size <= 4)
    val ecc = ErrorCorrectionLevel.valueOf(request["ecc"].asString)
    val mask = request["mask"].asInt
    val records = (0 until (1 shl priority.size)).flatMap { assignment ->
        val matrix = parsed.matrix.copy()
        priority.forEachIndexed { bit, (x, y) ->
            matrix.set(x, y, if ((assignment and (1 shl bit)) == 0) ModuleValue.ZERO else ModuleValue.ONE,
                1f, ModuleSource.USER_SOFT)
        }
        val original = QrBlockInterleaver.deinterleave(CodewordExtractor.extract(matrix, parsed.version, mask),
            parsed.version, ecc)
        listOf(false, true).map { single ->
            val blocks = original.copyDeep()
            val constraints = if (single) PayloadConstraintSolver.apply(blocks, parsed.version, trustKnownCount = true) else null
            val reports = blocks.blocks.mapIndexed { index, block ->
                val erased = block.unknownMasks.indices.filter { block.unknownMasks[it] != 0 }
                val minimumBits = erased.map { block.unknownMasks[it].countOneBits() }.sorted()
                    .take(maxOf(0, erased.size - blocks.ecSymbolsPerBlock)).sum()
                val ordinary = try {
                    ReedSolomonErasures().decode(block.codewords, blocks.ecSymbolsPerBlock, erased.toIntArray())
                    true
                } catch (_: ReedSolomonErasures.DecodeException) { false }
                var failure: String? = null
                val assigned = try {
                    PartialCodewordSolver(ReedSolomonErasures()).decodeCandidates(block, blocks.ecSymbolsPerBlock,
                        maxEnumeratedBits = PartialBitSearchLimit.DEFAULT).size
                } catch (error: ReedSolomonErasures.DecodeException) {
                    failure = error.reason.toString()
                    0
                }
                mapOf("blockIndex" to index, "erasures" to erased.size, "eccSymbols" to blocks.ecSymbolsPerBlock,
                    "minimumBitsToReachRsErasureCapacity" to minimumBits, "ordinaryRsSucceeded" to ordinary,
                    "assignedOrdinaryRsCandidates" to assigned, "assignedOrdinaryRsFailure" to failure)
            }
            mapOf("priorityAssignment" to assignment, "single" to single,
                "filledBits" to (constraints?.filledBits ?: 0), "blocks" to reports)
        }
    }
    output.parentFile.mkdirs()
    output.writeText(GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(records) + "\n")
}
