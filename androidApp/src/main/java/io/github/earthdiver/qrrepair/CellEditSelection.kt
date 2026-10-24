package io.github.earthdiver.qrrepair

import io.github.earthdiver.qrrepair.core.Coord

/** Immutable selection shared by the current editor and its undo/redo transactions. */
internal class CellEditSelection private constructor(
    val cells: Set<Coord>,
    val isRectangle: Boolean,
    val focus: Coord?,
) {
    fun transformed(transform: (Coord) -> Coord): CellEditSelection {
        val transformed = if (cells is RectangleCells) {
            val a = transform(Coord(cells.left, cells.top))
            val b = transform(Coord(cells.right, cells.bottom))
            RectangleCells(minOf(a.x, b.x), minOf(a.y, b.y), maxOf(a.x, b.x), maxOf(a.y, b.y))
        } else cells.mapTo(linkedSetOf(), transform)
        return CellEditSelection(transformed, isRectangle, focus?.let(transform))
    }

    companion object {
        val EMPTY = CellEditSelection(emptySet(), false, null)

        fun of(coords: Collection<Coord>, asRectangle: Boolean, focus: Coord? = null): CellEditSelection {
            if (coords.isEmpty()) return EMPTY
            val owned = coords.toSet()
            val bounds = RectangleCells(owned.minOf { it.x }, owned.minOf { it.y },
                owned.maxOf { it.x }, owned.maxOf { it.y })
            val rectangular = owned.size == bounds.size
            return CellEditSelection(if (rectangular) bounds else owned, asRectangle && rectangular,
                focus?.takeIf { it in owned } ?: owned.first())
        }
    }
}

/** A large rectangle costs four coordinates in history, rather than one object per cell. */
private class RectangleCells(val left: Int, val top: Int, val right: Int, val bottom: Int) : AbstractSet<Coord>() {
    override val size: Int get() = (right - left + 1) * (bottom - top + 1)
    override fun contains(element: Coord): Boolean = element.x in left..right && element.y in top..bottom
    override fun iterator(): Iterator<Coord> = sequence {
        for (y in top..bottom) for (x in left..right) yield(Coord(x, y))
    }.iterator()
}
