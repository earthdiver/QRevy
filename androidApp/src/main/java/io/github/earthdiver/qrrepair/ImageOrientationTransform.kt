package io.github.earthdiver.qrrepair

internal data class ImageOrientationTransform(
    val rotationDegrees: Float = 0f,
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
)

/** EXIF orientation values are standardized as 1..8. */
internal fun imageOrientationTransform(orientation: Int): ImageOrientationTransform = when (orientation) {
    2 -> ImageOrientationTransform(scaleX = -1f)
    3 -> ImageOrientationTransform(rotationDegrees = 180f)
    4 -> ImageOrientationTransform(scaleY = -1f)
    5 -> ImageOrientationTransform(rotationDegrees = 90f, scaleX = -1f)
    6 -> ImageOrientationTransform(rotationDegrees = 90f)
    7 -> ImageOrientationTransform(rotationDegrees = -90f, scaleX = -1f)
    8 -> ImageOrientationTransform(rotationDegrees = -90f)
    else -> ImageOrientationTransform()
}
