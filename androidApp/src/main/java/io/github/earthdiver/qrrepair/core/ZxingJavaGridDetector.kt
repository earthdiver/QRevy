package io.github.earthdiver.qrrepair.core

object ZxingJavaGridDetector {
    /** Verified Java geometry is a fallback when the native reader supplies no boundaries. */
    fun detect(img: LumaImage, cancellation: RecoveryCancellation = RecoveryCancellation.NONE): List<ImageQuad> =
        listOf(false, true).flatMap { inverted ->
            cancellation.check()
            boundary(img, inverted, cancellation).also { cancellation.check() }
        }
    private fun boundary(img: LumaImage, inverted: Boolean, cancellation: RecoveryCancellation): List<ImageQuad> {
        val bits = com.google.zxing.common.BitMatrix(img.width, img.height)
        for (y in 0 until img.height) {
            cancellation.check()
            for (x in 0 until img.width)
                if ((img.luma(x,y) <= img.threshold) != inverted) bits.set(x,y)
        }
        fun affine(version: Int, points: List<PointD>): GridGeometry {
            fun finder(i: Int) = FinderCandidate(points[i].x, points[i].y, 1.0, 0.0, 1.0, 1)
            return GridGeometry.fromAffine(version, FinderRole.TOP_LEFT, mapOf(
                FinderRole.TOP_LEFT to finder(1), FinderRole.TOP_RIGHT to finder(2), FinderRole.BOTTOM_LEFT to finder(0)))
        }
        fun outer(g: GridGeometry): ImageQuad {
            val last = g.dimension - .5
            return ImageQuad(g.moduleToImage(-.5,-.5), g.moduleToImage(last,-.5),
                g.moduleToImage(last,last), g.moduleToImage(-.5,last))
        }
        val detected = try {
            com.google.zxing.qrcode.detector.Detector(bits).detect(mapOf(com.google.zxing.DecodeHintType.TRY_HARDER to true))
        } catch (_: Exception) { null }
        cancellation.check()
        if (detected != null) {
            val points = detected.points.map { PointD(it.x.toDouble(), it.y.toDouble()) }
            val n = detected.bits.width
            val version = (n - 17) / 4
            if (version in 1..40 && points.size >= 3) {
                val base = affine(version, points)
                val projected = if (points.size >= 4) try { outer(GridGeometry.fromFourCorrespondences(base,
                    listOf(PointD(3.0,3.0), PointD(n-4.0,3.0), PointD(3.0,n-4.0), PointD(n-7.0,n-7.0)),
                    listOf(points[1],points[2],points[0],points[3]))) } catch (_: IllegalArgumentException) { null } else null
                // An apparent alignment pattern inside damage must not override verified Finders.
                if (projected != null && InitialGridEstimator.fromBoundary(img, projected, cancellation) != null) return listOf(projected)
                val simple = outer(base)
                val verified = InitialGridEstimator.fromBoundary(img, simple, cancellation)
                if (verified != null && (points.size == 3 || verified.autoAssignedFinders.size == 3)) return listOf(simple)
            }
        }
        // ZXing's full detector can throw while sampling an invalid alignment fit.
        // Recover the observed centers independently; every proposed version still
        // has to pass the upstream Finder, separator and timing-pattern checks.
        val info = try { com.google.zxing.qrcode.detector.FinderGeometry.find(bits) }
            catch (_: Exception) { return emptyList() }
        cancellation.check()
        val finders = listOf(info.bottomLeft, info.topLeft, info.topRight)
        val points = finders.map { PointD(it.x.toDouble(), it.y.toDouble()) }
        val pitch = finders.map { it.estimatedModuleSize.toDouble() }.average()
        if (!pitch.isFinite() || pitch <= 0) return emptyList()
        val dimension = (points[1].distanceTo(points[0]) + points[1].distanceTo(points[2])) / (2 * pitch) + 7
        val version = kotlin.math.round((dimension - 17) / 4).toInt()
        return (version-1..version+1).filter { it in 1..40 }.map { outer(affine(it, points)) }
            .filter { InitialGridEstimator.fromBoundary(img, it, cancellation)?.autoAssignedFinders?.size == 3 }
    }

}
