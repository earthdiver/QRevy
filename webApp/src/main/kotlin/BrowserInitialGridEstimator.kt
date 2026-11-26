package qrevy.web

import io.github.earthdiver.qrrepair.core.*

internal object BrowserInitialGridEstimator {
    fun estimate(
        image: LumaImage,
        boundaries: List<ImageQuad>,
        cancellation: RecoveryCancellation = RecoveryCancellation.NONE,
        trace: GridEstimationTrace? = null,
    ): InitialGridEstimate? = InitialGridEstimator.estimate(image, boundaries, cancellation, trace)
}
