package io.github.earthdiver.qrrepair.core

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/** Three-state representation used throughout the repair pipeline. */
enum class ModuleValue {
    ZERO, ONE, ERASED;
    fun bitOrNull(): Int? = when (this) { ZERO -> 0; ONE -> 1; ERASED -> null }
    fun next(): ModuleValue = when (this) { ZERO -> ONE; ONE -> ERASED; ERASED -> ZERO }
}

enum class ModuleSource { IMAGE, USER, RECOVERED, STRUCTURAL, CONSTRAINT }

data class QrCell(
    var value: ModuleValue = ModuleValue.ERASED,
    var confidence: Float = 0f,
    var source: ModuleSource = ModuleSource.IMAGE,
    /** User-authorized 0/1 priority search; semantically still an erasure until enumerated. */
    var prioritySearch: Boolean = false,
)

class QrMatrix(val dimension: Int) {
    init { require(dimension in 21..177 && (dimension - 17) % 4 == 0) { "Invalid QR dimension: $dimension" } }
    private val cells = Array(dimension * dimension) { QrCell() }
    operator fun get(x: Int, y: Int): QrCell = cells[y * dimension + x]
    fun set(x: Int, y: Int, value: ModuleValue, confidence: Float, source: ModuleSource) {
        require(confidence.isFinite() && confidence in 0f..1f) { "Invalid module confidence: $confidence" }
        val c = get(x, y); c.value = value; c.confidence = confidence; c.source = source
        if (value != ModuleValue.ERASED) c.prioritySearch = false
    }
    fun setPrioritySearch(x: Int, y: Int, enabled: Boolean) {
        val cell = get(x, y)
        require(!enabled || cell.value == ModuleValue.ERASED) { "Priority search requires an erased module" }
        cell.prioritySearch = enabled
    }
    fun prioritySearchCoordinates(): List<Pair<Int, Int>> = buildList {
        for (y in 0 until dimension) for (x in 0 until dimension) if (get(x, y).prioritySearch) add(x to y)
    }
    fun copy(): QrMatrix {
        val out = QrMatrix(dimension)
        for (y in 0 until dimension) for (x in 0 until dimension) {
            val c = get(x, y); out.set(x, y, c.value, c.confidence, c.source)
            out.setPrioritySearch(x, y, c.prioritySearch)
        }
        return out
    }
    fun erasureCount(): Int = cells.count { it.value == ModuleValue.ERASED }
}

data class FinderCandidate(
    val centerX: Double,
    val centerY: Double,
    val moduleSize: Double,
    /** QR x-axis direction, modulo 90 degrees. */
    val angleRad: Double,
    val score: Double,
    val observations: Int,
    val evidence: FinderEvidence = FinderEvidence.COMPLETE_CROSS,
    /** Logical polarity and preprocessing source that produced this image-verified candidate. */
    val polarity: FinderPolarity = FinderPolarity.UNKNOWN,
    val detectionSource: FinderDetectionSource = FinderDetectionSource.RAW,
)

enum class FinderEvidence { COMPLETE_CROSS, PARTIAL_CORE, MANUAL_HINT }
enum class FinderPolarity { NORMAL, INVERTED, UNKNOWN }
enum class FinderDetectionSource { RAW, LOCAL_BINARY, MANUAL }

/** Explanatory ranking only; this is not a calibrated probability. */
fun FinderCandidate.reliabilityScore(): Double =
    score + observations.coerceAtMost(20) * .005 + when (evidence) {
        FinderEvidence.COMPLETE_CROSS -> .03
        FinderEvidence.PARTIAL_CORE -> 0.0
        FinderEvidence.MANUAL_HINT -> -.20
    }

enum class FinderRole { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT }
enum class GeometryModel { SIMILARITY, TWO_FINDER, AFFINE, PROJECTIVE, MANUAL_PROJECTIVE }

data class PointD(val x: Double, val y: Double) {
    operator fun plus(other: PointD) = PointD(x + other.x, y + other.y)
    operator fun minus(other: PointD) = PointD(x - other.x, y - other.y)
    operator fun times(s: Double) = PointD(x * s, y * s)
    fun distanceTo(other: PointD): Double = hypot(x - other.x, y - other.y)
}

data class ImageQuad(val topLeft: PointD, val topRight: PointD, val bottomRight: PointD, val bottomLeft: PointD) {
    fun asList(): List<PointD> = listOf(topLeft, topRight, bottomRight, bottomLeft)
    fun withCorner(index: Int, point: PointD): ImageQuad = when (index) {
        0 -> copy(topLeft = point)
        1 -> copy(topRight = point)
        2 -> copy(bottomRight = point)
        3 -> copy(bottomLeft = point)
        else -> error("corner index must be 0..3")
    }
}

/** Assigns logical QR corner names to an already clockwise physical boundary. */
fun ImageQuad.assignedFromFinder(point: PointD, role: FinderRole): ImageQuad {
    val physical = asList()
    val physicalIndex = physical.indices.minBy { physical[it].distanceTo(point) }
    val logicalIndex = when (role) {
        FinderRole.TOP_LEFT -> 0
        FinderRole.TOP_RIGHT -> 1
        FinderRole.BOTTOM_LEFT -> 3
    }
    val shift = (physicalIndex - logicalIndex + 4) % 4
    val assigned = List(4) { physical[(it + shift) % 4] }
    return ImageQuad(assigned[0], assigned[1], assigned[2], assigned[3])
}

data class GridGeometry(
    val version: Int,
    val role: FinderRole,
    val finderCenterX: Double,
    val finderCenterY: Double,
    val modulePitch: Double,
    val angleRad: Double,
    /** Image positions of module-center corners (0,0), (d-1,0), (d-1,d-1), (0,d-1). */
    val imageQuad: ImageQuad? = null,
    val model: GeometryModel = GeometryModel.SIMILARITY,
) {
    init {
        require(version in 1..40) { "Invalid QR version: $version" }
        require(finderCenterX.isFinite() && finderCenterY.isFinite()) { "Finder center must be finite" }
        require(modulePitch.isFinite() && modulePitch > 0.0) { "Module pitch must be positive and finite" }
        require(angleRad.isFinite()) { "Geometry angle must be finite" }
        imageQuad?.asList()?.forEach { p -> require(p.x.isFinite() && p.y.isFinite()) { "Image quad must be finite" } }
    }

    val dimension: Int get() = 17 + 4 * version

    private val finderModuleX: Double get() = when (role) {
        FinderRole.TOP_LEFT, FinderRole.BOTTOM_LEFT -> 3.0
        FinderRole.TOP_RIGHT -> (dimension - 4).toDouble()
    }
    private val finderModuleY: Double get() = when (role) {
        FinderRole.TOP_LEFT, FinderRole.TOP_RIGHT -> 3.0
        FinderRole.BOTTOM_LEFT -> (dimension - 4).toDouble()
    }

    private val projective: Homography? by lazy {
        imageQuad?.let { q ->
            val d = (dimension - 1).toDouble()
            Homography.fromCorrespondences(
                listOf(PointD(0.0, 0.0), PointD(d, 0.0), PointD(d, d), PointD(0.0, d)),
                q.asList(),
            )
        }
    }
    private val inverseProjective: Homography? by lazy { projective?.inverse() }

    fun finderModulePoint(which: FinderRole = role): PointD = when (which) {
        FinderRole.TOP_LEFT -> PointD(3.0, 3.0)
        FinderRole.TOP_RIGHT -> PointD((dimension - 4).toDouble(), 3.0)
        FinderRole.BOTTOM_LEFT -> PointD(3.0, (dimension - 4).toDouble())
    }

    fun moduleToImage(x: Double, y: Double): PointD {
        projective?.let { return it.map(x, y) }
        val dx = (x - finderModuleX) * modulePitch
        val dy = (y - finderModuleY) * modulePitch
        val c = cos(angleRad); val s = sin(angleRad)
        return PointD(finderCenterX + c * dx - s * dy, finderCenterY + s * dx + c * dy)
    }

    fun imageToModule(px: Double, py: Double): PointD {
        inverseProjective?.let { return it.map(px, py) }
        val dx = px - finderCenterX; val dy = py - finderCenterY
        val c = cos(angleRad); val s = sin(angleRad)
        return PointD((c * dx + s * dy) / modulePitch + finderModuleX, (-s * dx + c * dy) / modulePitch + finderModuleY)
    }

    fun nearestModule(px: Double, py: Double): Pair<Int, Int>? {
        val p = imageToModule(px, py)
        val x = p.x.roundToInt(); val y = p.y.roundToInt()
        return if (x in 0 until dimension && y in 0 until dimension) x to y else null
    }

    fun localModulePitch(x: Double = finderModuleX, y: Double = finderModuleY): Double {
        val p = moduleToImage(x, y)
        val px = moduleToImage(x + 1.0, y)
        val py = moduleToImage(x, y + 1.0)
        return (p.distanceTo(px) + p.distanceTo(py)) / 2.0
    }

    fun moduleCenterQuad(): ImageQuad = imageQuad ?: ImageQuad(
        moduleToImage(0.0, 0.0),
        moduleToImage((dimension - 1).toDouble(), 0.0),
        moduleToImage((dimension - 1).toDouble(), (dimension - 1).toDouble()),
        moduleToImage(0.0, (dimension - 1).toDouble()),
    )

    fun asProjective(manual: Boolean = false): GridGeometry = copy(
        imageQuad = moduleCenterQuad(),
        model = if (manual) GeometryModel.MANUAL_PROJECTIVE else GeometryModel.PROJECTIVE,
    ).reanchored()

    /** Recompute the legacy anchor fields from the active mapping. */
    fun reanchored(): GridGeometry {
        val fp = finderModulePoint(role)
        val fi = moduleToImage(fp.x, fp.y)
        val px = moduleToImage(fp.x + 1.0, fp.y)
        val py = moduleToImage(fp.x, fp.y + 1.0)
        return copy(
            finderCenterX = fi.x,
            finderCenterY = fi.y,
            modulePitch = (fi.distanceTo(px) + fi.distanceTo(py)) / 2.0,
            angleRad = atan2(px.y - fi.y, px.x - fi.x),
        )
    }

    fun withCorner(index: Int, imagePoint: PointD): GridGeometry {
        val q = moduleCenterQuad().withCorner(index, imagePoint)
        val finderPoint = when (role) {
            FinderRole.TOP_LEFT -> PointD(3.0, 3.0)
            FinderRole.TOP_RIGHT -> PointD((dimension - 4).toDouble(), 3.0)
            FinderRole.BOTTOM_LEFT -> PointD(3.0, (dimension - 4).toDouble())
        }
        val h = Homography.fromCorrespondences(
            listOf(PointD(0.0, 0.0), PointD((dimension - 1).toDouble(), 0.0), PointD((dimension - 1).toDouble(), (dimension - 1).toDouble()), PointD(0.0, (dimension - 1).toDouble())),
            q.asList(),
        )
        val f = h.map(finderPoint)
        return copy(imageQuad = q, finderCenterX = f.x, finderCenterY = f.y, model = GeometryModel.MANUAL_PROJECTIVE)
    }

    fun adjusted(
        dxPixels: Double = 0.0,
        dyPixels: Double = 0.0,
        pitchScale: Double = 1.0,
        angleDeltaRad: Double = 0.0,
    ): GridGeometry {
        if (imageQuad == null) return copy(
            finderCenterX = finderCenterX + dxPixels,
            finderCenterY = finderCenterY + dyPixels,
            modulePitch = modulePitch * pitchScale,
            angleRad = angleRad + angleDeltaRad,
        )
        val anchor = PointD(finderCenterX, finderCenterY)
        val c = cos(angleDeltaRad); val s = sin(angleDeltaRad)
        fun transform(p: PointD): PointD {
            val dx = (p.x - anchor.x) * pitchScale
            val dy = (p.y - anchor.y) * pitchScale
            return PointD(anchor.x + c * dx - s * dy + dxPixels, anchor.y + s * dx + c * dy + dyPixels)
        }
        val q = imageQuad
        return copy(
            finderCenterX = finderCenterX + dxPixels,
            finderCenterY = finderCenterY + dyPixels,
            modulePitch = modulePitch * pitchScale,
            angleRad = angleRad + angleDeltaRad,
            imageQuad = ImageQuad(transform(q.topLeft), transform(q.topRight), transform(q.bottomRight), transform(q.bottomLeft)),
        )
    }

    companion object {
        fun fromTwoFinders(version: Int, roleA: FinderRole, a: FinderCandidate, roleB: FinderRole, b: FinderCandidate): GridGeometry {
            require(roleA != roleB)
            val d = 17 + 4 * version
            fun modulePoint(role: FinderRole) = when (role) {
                FinderRole.TOP_LEFT -> PointD(3.0, 3.0)
                FinderRole.TOP_RIGHT -> PointD((d - 4).toDouble(), 3.0)
                FinderRole.BOTTOM_LEFT -> PointD(3.0, (d - 4).toDouble())
            }
            val ma = modulePoint(roleA); val mb = modulePoint(roleB)
            val ia = PointD(a.centerX, a.centerY); val ib = PointD(b.centerX, b.centerY)
            val mdx = mb.x - ma.x; val mdy = mb.y - ma.y
            val idx = ib.x - ia.x; val idy = ib.y - ia.y
            val scale = hypot(idx, idy) / hypot(mdx, mdy)
            val angle = atan2(idy, idx) - atan2(mdy, mdx)
            return GridGeometry(version, roleA, ia.x, ia.y, scale, angle, null, GeometryModel.TWO_FINDER)
        }

        fun fromAffine(version: Int, roleAnchor: FinderRole, roleToFinder: Map<FinderRole, FinderCandidate>): GridGeometry {
            require(roleToFinder.keys.containsAll(FinderRole.entries))
            val d = 17 + 4 * version
            fun mp(role: FinderRole) = when (role) {
                FinderRole.TOP_LEFT -> PointD(3.0, 3.0)
                FinderRole.TOP_RIGHT -> PointD((d - 4).toDouble(), 3.0)
                FinderRole.BOTTOM_LEFT -> PointD(3.0, (d - 4).toDouble())
            }
            val roles = FinderRole.entries
            val affine = Affine2D.fromThree(roles.map(::mp), roles.map { PointD(roleToFinder.getValue(it).centerX, roleToFinder.getValue(it).centerY) })
            val last = (d - 1).toDouble()
            val quad = ImageQuad(affine.map(PointD(0.0, 0.0)), affine.map(PointD(last, 0.0)), affine.map(PointD(last, last)), affine.map(PointD(0.0, last)))
            val anchor = roleToFinder.getValue(roleAnchor)
            val pitchX = affine.map(PointD(1.0, 0.0)).distanceTo(affine.map(PointD(0.0, 0.0)))
            val pitchY = affine.map(PointD(0.0, 1.0)).distanceTo(affine.map(PointD(0.0, 0.0)))
            val angle = atan2(affine.map(PointD(1.0, 0.0)).y - affine.map(PointD(0.0, 0.0)).y, affine.map(PointD(1.0, 0.0)).x - affine.map(PointD(0.0, 0.0)).x)
            return GridGeometry(version, roleAnchor, anchor.centerX, anchor.centerY, (pitchX + pitchY) / 2.0, angle, quad, GeometryModel.AFFINE)
        }

        fun fromFourCorrespondences(
            base: GridGeometry,
            modulePoints: List<PointD>,
            imagePoints: List<PointD>,
            model: GeometryModel = GeometryModel.PROJECTIVE,
        ): GridGeometry {
            val h = Homography.fromCorrespondences(modulePoints, imagePoints)
            val last = (base.dimension - 1).toDouble()
            val quad = ImageQuad(h.map(0.0, 0.0), h.map(last, 0.0), h.map(last, last), h.map(0.0, last))
            val fp = base.finderModulePoint(base.role)
            val fi = h.map(fp)
            return base.copy(
                finderCenterX = fi.x,
                finderCenterY = fi.y,
                modulePitch = (h.map(fp.x + 1.0, fp.y).distanceTo(fi) + h.map(fp.x, fp.y + 1.0).distanceTo(fi)) / 2.0,
                angleRad = atan2(h.map(fp.x + 1.0, fp.y).y - fi.y, h.map(fp.x + 1.0, fp.y).x - fi.x),
                imageQuad = quad,
                model = model,
            )
        }
    }
}
