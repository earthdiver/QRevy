package io.github.earthdiver.qrrepair.core

/** Keeps the finger-to-handle offset stable throughout a drag gesture. */
internal fun draggedHandlePoint(startHandle: PointD, startTouch: PointD, currentTouch: PointD): PointD =
    PointD(
        startHandle.x + currentTouch.x - startTouch.x,
        startHandle.y + currentTouch.y - startTouch.y,
    )
