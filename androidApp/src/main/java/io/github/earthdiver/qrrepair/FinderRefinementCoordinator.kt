package io.github.earthdiver.qrrepair

import io.github.earthdiver.qrrepair.core.FinderCandidate
import io.github.earthdiver.qrrepair.core.FinderRefinement
import io.github.earthdiver.qrrepair.core.FinderRefinementOutcome
import io.github.earthdiver.qrrepair.core.FinderRole
import io.github.earthdiver.qrrepair.core.ImageQuad
import io.github.earthdiver.qrrepair.core.LumaImage
import io.github.earthdiver.qrrepair.core.PointD
import io.github.earthdiver.qrrepair.core.PreprocessSettings
import io.github.earthdiver.qrrepair.core.refineFinderPlacement
import io.github.earthdiver.qrrepair.ui.BitmapUtils
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger

/** Publishes Finder refinements only while their request and source image remain current. */
internal class FinderRefinementCoordinator(
    private val executor: Executor,
    private val dispatchToUi: (() -> Unit) -> Unit,
    private val generation: AtomicInteger,
    private val currentImage: () -> BitmapUtils.Prepared,
    private val refine: (LumaImage, PreprocessSettings, PointD, FinderRole?, ImageQuad?) -> FinderRefinement = ::refineFinderPlacement,
) {
    fun request(
        settings: PreprocessSettings,
        hint: PointD,
        role: FinderRole,
        physicalGrid: ImageQuad?,
        onNotFound: (FinderRefinementOutcome) -> Unit,
        onRefined: (FinderCandidate) -> Unit,
        confirm: (accept: () -> Unit) -> Unit,
    ) {
        val token = generation.incrementAndGet()
        val image = currentImage()
        fun current() = token == generation.get() && currentImage() === image
        executor.execute {
            val refinement = refine(image.luma, settings, hint, role, physicalGrid)
            dispatchToUi {
                if (!current()) return@dispatchToUi
                val candidate = refinement.candidate
                when {
                    candidate == null -> onNotFound(refinement.outcome)
                    // Confirmation can outlive a newer tap, a removed Finder, or the dialog.
                    refinement.needsConfirmation -> confirm { if (current()) onRefined(candidate) }
                    else -> onRefined(candidate)
                }
            }
        }
    }
}
