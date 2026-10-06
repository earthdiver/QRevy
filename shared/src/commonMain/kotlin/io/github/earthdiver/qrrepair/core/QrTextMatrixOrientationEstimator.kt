package io.github.earthdiver.qrrepair.core

data class QrTextMatrixOrientation(
    /** Clockwise rotation from logical QR coordinates into the imported matrix. */
    val quarterTurns: Int,
    /** Verified centers in the imported matrix's module coordinates. */
    val finderCenters: Map<FinderRole, Coord>,
)

/** Structure checks on a known grid; never fills unknown cells or proves payload validity. */
object QrTextMatrixOrientationEstimator {
    private class Evidence {
        var total = 0
        var known = 0
        var errors = 0
        var black = 0

        fun observe(value: ModuleValue, expectedBlack: Boolean) {
            total++
            if (value == ModuleValue.ERASED) return
            known++
            if (value == ModuleValue.ONE) black++
            if ((value == ModuleValue.ONE) != expectedBlack) errors++
        }

        // Unknown cells supply no agreement. This is a ranking heuristic, not a probability.
        val cost: Double get() = (errors + .5 * (total - known)) / total
    }

    fun estimate(matrix: QrMatrix): QrTextMatrixOrientation? {
        val n = matrix.dimension
        val origins = mapOf(
            FinderRole.TOP_LEFT to Coord(0, 0),
            FinderRole.TOP_RIGHT to Coord(n - 7, 0),
            FinderRole.BOTTOM_LEFT to Coord(0, n - 7),
        )
        fun source(x: Int, y: Int, rotation: Int): Coord = when (rotation) {
            0 -> Coord(x, y)
            1 -> Coord(n - 1 - y, x)
            2 -> Coord(n - 1 - x, n - 1 - y)
            else -> Coord(y, n - 1 - x)
        }
        data class Choice(val rotation: Int, val cost: Double, val sufficient: Boolean)
        val choices = (0..3).map { rotation ->
            fun sample(x: Int, y: Int): ModuleValue {
                val p = source(x, y, rotation)
                return matrix[p.x, p.y].value
            }
            val finders = mutableListOf<Evidence>()
            val separators = mutableListOf<Evidence>()
            for (origin in origins.values) {
                val finder = Evidence()
                for (y in 0..6) for (x in 0..6) {
                    val black = x == 0 || y == 0 || x == 6 || y == 6 || (x in 2..4 && y in 2..4)
                    finder.observe(sample(origin.x + x, origin.y + y), black)
                }
                finders += finder
                val separator = Evidence()
                val separatorX = origin.x + if (origin.x == 0) 7 else -1
                val separatorY = origin.y + if (origin.y == 0) 7 else -1
                for (i in 0..6) {
                    separator.observe(sample(separatorX, origin.y + i), false)
                    separator.observe(sample(origin.x + i, separatorY), false)
                }
                separator.observe(sample(separatorX, separatorY), false)
                separators += separator
            }
            val horizontal = Evidence()
            val vertical = Evidence()
            for (i in 8..n - 9) {
                horizontal.observe(sample(i, 6), i % 2 == 0)
                vertical.observe(sample(6, i), i % 2 == 0)
            }
            val timing = listOf(horizontal, vertical)
            // Require observable evidence for every assigned Finder. Small gaps and errors
            // are allowed independently in each pattern, never averaged across missing ones.
            val sufficient = finders.all { it.known >= it.total * .75 && it.errors <= 2 } &&
                separators.all { it.known >= 10 && it.errors <= 1 } &&
                timing.all { it.known >= maxOf(3.0, it.total * .6) &&
                    it.black in 1 until it.known && it.errors <= maxOf(1, it.known / 10) }
            val cost = .6 * finders.map { it.cost }.average() +
                .15 * separators.map { it.cost }.average() + .25 * timing.map { it.cost }.average()
            Choice(rotation, cost, sufficient)
        }
        val ordered = choices.sortedBy { it.cost }
        val best = ordered.first()
        // Include insufficient alternatives in the margin check to keep ambiguous input manual.
        if (!best.sufficient || ordered[1].cost - best.cost < .08) return null
        val rotation = best.rotation
        return QrTextMatrixOrientation(rotation,
            origins.mapValues { (_, origin) -> source(origin.x + 3, origin.y + 3, rotation) })
    }
}
