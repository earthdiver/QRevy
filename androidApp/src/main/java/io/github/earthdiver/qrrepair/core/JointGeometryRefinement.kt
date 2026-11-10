package io.github.earthdiver.qrrepair.core

import com.google.zxing.qrcode.decoder.Version

/** Replaces one existing sampling candidate; fitting never reads repaired bits or manual edits. */
internal fun jointlyRefinedGeometry(
    image: LumaImage,
    geometry: GridGeometry,
    profile: SamplingProfile,
    stopped: () -> Boolean,
): GridGeometry? = JointPatternRefiner.refine(image, geometry,
    Version.getVersionForNumber(geometry.version).alignmentPatternCenters, profile.inverted, stopped)?.geometry
