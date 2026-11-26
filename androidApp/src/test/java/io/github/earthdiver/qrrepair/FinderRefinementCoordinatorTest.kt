package io.github.earthdiver.qrrepair

import android.graphics.Bitmap
import io.github.earthdiver.qrrepair.core.*
import io.github.earthdiver.qrrepair.ui.BitmapUtils
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FinderRefinementCoordinatorTest {
    private class Fixture {
        private val images = mutableListOf<Bitmap>()
        fun newImage(): BitmapUtils.Prepared = BitmapUtils.prepare(
            Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888).also { images += it })
        var image = newImage()
        val workers = ArrayDeque<Runnable>()
        val ui = ArrayDeque<() -> Unit>()
        val generation = AtomicInteger()
        val candidate = FinderCandidate(14.0, 14.0, 3.0, 0.0, 1.0, 1)
        var refinement = FinderRefinement(FinderRefinementOutcome.REFINED, candidate, tapOnFinder = true)
        val applied = mutableListOf<FinderCandidate>()
        val missing = mutableListOf<FinderRefinementOutcome>()
        val confirmations = mutableListOf<() -> Unit>()
        val settings = PreprocessSettings(version = 2, automaticThresholds = false)
        val hint = PointD(10.0, 11.0)
        val physicalGrid = ImageQuad(PointD(0.0, 0.0), PointD(39.0, 0.0), PointD(39.0, 39.0), PointD(0.0, 39.0))
        var searchedImage: LumaImage? = null
        val coordinator = FinderRefinementCoordinator(
            Executor { workers.addLast(it) }, { ui.addLast(it) }, generation, { image },
            refine = { luma, actualSettings, actualHint, actualRole, actualGrid ->
                searchedImage = luma
                assertSame(settings, actualSettings)
                assertEquals(hint, actualHint)
                assertEquals(FinderRole.TOP_LEFT, actualRole)
                assertEquals(physicalGrid, actualGrid)
                refinement
            },
        )

        fun request() = coordinator.request(settings, hint, FinderRole.TOP_LEFT, physicalGrid,
            { missing += it }, { applied += it }, { confirmations += it })
        fun complete() { workers.removeFirst().run(); ui.removeFirst().invoke() }
        fun release() { images.forEach { if (!it.isRecycled) it.recycle() } }
    }

    private fun withFixture(action: (Fixture) -> Unit) {
        val fixture = Fixture()
        try { action(fixture) } finally { fixture.release() }
    }

    @Test fun currentRefinementUsesCapturedInputsAndPublishesOnUi() = withFixture { fixture ->
        fixture.request()
        assertNull(fixture.searchedImage)
        fixture.workers.removeFirst().run()
        assertSame(fixture.image.luma, fixture.searchedImage)
        assertTrue(fixture.applied.isEmpty())
        fixture.ui.removeFirst().invoke()
        assertEquals(listOf(fixture.candidate), fixture.applied)
        assertTrue(fixture.confirmations.isEmpty())
    }

    @Test fun newerTapSupersedesARefinementWaitingForUiDispatch() = withFixture { fixture ->
        fixture.request()
        fixture.workers.removeFirst().run()
        fixture.request()
        fixture.ui.removeFirst().invoke()
        assertTrue(fixture.applied.isEmpty())
        assertTrue(fixture.missing.isEmpty())
        assertTrue(fixture.confirmations.isEmpty())
        fixture.complete()
        assertEquals(listOf(fixture.candidate), fixture.applied)
    }

    @Test fun invalidatingRemovedOrClosedWorkDiscardsBothSuccessAndFailure() {
        for (outcome in FinderRefinementOutcome.entries) withFixture { fixture ->
            fixture.refinement = if (outcome == FinderRefinementOutcome.REFINED) fixture.refinement
                else FinderRefinement(outcome)
            fixture.request()
            fixture.generation.incrementAndGet()
            fixture.complete()
            assertTrue(fixture.applied.isEmpty())
            assertTrue(fixture.missing.isEmpty())
            assertTrue(fixture.confirmations.isEmpty())
        }
    }

    @Test fun replacingTheImageRejectsResultsEvenWithoutAGenerationChange() = withFixture { fixture ->
        val captured = fixture.image
        fixture.request()
        fixture.image = fixture.newImage()
        fixture.complete()
        assertSame(captured.luma, fixture.searchedImage)
        assertTrue(fixture.applied.isEmpty())
        assertTrue(fixture.confirmations.isEmpty())
    }

    @Test fun nearbyCandidateWaitsForConfirmationAndChecksCurrencyAgain() {
        for (change in listOf("none", "generation", "image")) withFixture { fixture ->
            fixture.refinement = fixture.refinement.copy(tapOnFinder = false)
            fixture.request()
            fixture.complete()
            assertTrue(fixture.applied.isEmpty())
            assertEquals(1, fixture.confirmations.size)
            when (change) {
                "generation" -> fixture.generation.incrementAndGet()
                "image" -> fixture.image = fixture.newImage()
            }
            fixture.confirmations.single().invoke()
            assertEquals(if (change == "none") listOf(fixture.candidate) else emptyList<FinderCandidate>(), fixture.applied)
        }
    }

    @Test fun undetectedAndRejectedPatternsKeepTheirDistinctOutcomesWithoutApplyingACenter() {
        for (outcome in listOf(FinderRefinementOutcome.NOT_FOUND, FinderRefinementOutcome.REJECTED)) withFixture { fixture ->
            fixture.refinement = FinderRefinement(outcome)
            fixture.request()
            fixture.complete()
            assertEquals(listOf(outcome), fixture.missing)
            assertTrue(fixture.applied.isEmpty())
            assertTrue(fixture.confirmations.isEmpty())
        }
    }
}
