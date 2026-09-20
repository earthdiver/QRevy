package io.github.earthdiver.qrrepair.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PreprocessSettingsTest {
    @Test fun recoverySnapshotDetachesManualEditsAndPriorityMarkers() {
        val coord = Coord(10, 10)
        val edits = mutableMapOf(coord to ModuleValue.ERASED)
        val markers = mutableSetOf(coord)
        val finders = mutableMapOf(FinderRole.TOP_LEFT to PointD(3.0, 3.0))
        val settings = PreprocessSettings(preRecoveryEdits = edits,
            preRecoveryPrioritySearch = markers, manualFinders = finders)
        val snapshot = settings.recoverySnapshot()
        edits[coord] = ModuleValue.ONE
        markers.clear()
        finders.clear()
        assertEquals(ModuleValue.ERASED, snapshot.preRecoveryEdits[coord])
        assertEquals(setOf(coord), snapshot.preRecoveryPrioritySearch)
        assertEquals(1, snapshot.manualFinders.size)
    }

    @Test fun formatInformationErrorLimitAcceptsTheTwoCopyRange() {
        assertEquals(0, PreprocessSettings(formatMismatchLimit = 0).formatMismatchLimit)
        assertEquals(30, PreprocessSettings(formatMismatchLimit = 30).formatMismatchLimit)
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            PreprocessSettings(formatMismatchLimit = 31)
        }
    }

    @Test fun dataEccErrorLimitAcceptsTheQrCodewordRange() {
        assertEquals(0, PreprocessSettings(dataEccMismatchLimit = 0).dataEccMismatchLimit)
        assertEquals(MAX_QR_DATA_ECC_BITS, PreprocessSettings(dataEccMismatchLimit = MAX_QR_DATA_ECC_BITS).dataEccMismatchLimit)
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            PreprocessSettings(dataEccMismatchLimit = MAX_QR_DATA_ECC_BITS + 1)
        }
    }

    @Test fun formatConstraintCountsIndependentEccAndMaskChoices() {
        assertEquals(8, QrFormatConstraint(ecLevel = com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.Q).candidateCount)
        assertEquals(4, QrFormatConstraint(dataMask = 3).candidateCount)
        assertEquals(1, QrFormatConstraint(com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.Q, 3).candidateCount)
    }

    @Test fun approximateColorClusterIsUnknownBeforeLuminanceClassification() {
        val marker = 0xCC2233
        val lab = ColorLab.fromRgb(marker)
        val image = LumaImage(1, 1, intArrayOf(20), intArrayOf(marker))
        val cluster = UnknownColorCluster((lab.l * 100).toInt(), (lab.a * 100).toInt(), (lab.b * 100).toInt(), 800)
        val profile = SamplingProfile(PreprocessSettings(unknownColorClusters = listOf(cluster), useUnknownColorClusters = true), inverted = false)
        assertEquals(ModuleValue.ERASED, profile.classify(image, 20.0, 0.0, 0.0, 1.0))
        val disabled = profile.copy(settings = profile.settings.copy(useUnknownColorClusters = false))
        assertEquals(ModuleValue.ONE, disabled.classify(image, 20.0, 0.0, 0.0, 1.0))
    }
    @Test fun detectedColorClustersRemainDisabledByDefault() {
        val cluster = UnknownColorCluster(5000, 2000, 2000, 800)
        assertTrue(!PreprocessSettings(unknownColorClusters = listOf(cluster)).useUnknownColorClusters)
    }
    @Test fun geometryLockIsEnabledByDefault() {
        assertTrue(PreprocessSettings().lockCorners)
        assertTrue(PreprocessSettings().lockVersion)
        assertTrue(!PreprocessSettings().searchQuadNeighborhood)
    }

    @Test fun confirmedFinderPolarityRestrictsAutoVariants() {
        val normal = PreprocessSettings(
            polarity = PolarityMode.AUTO,
            confirmedFinderPolarity = FinderPolarity.NORMAL,
        )
        val inverted = normal.copy(confirmedFinderPolarity = FinderPolarity.INVERTED)

        assertEquals(listOf(false), normal.variants().map { it.inverted })
        assertEquals(listOf(true), inverted.variants().map { it.inverted })
    }

    @Test fun searchSummaryExplainsFixedVersionScope() {
        val settings = PreprocessSettings(
            version = 8,
            manualQuad = ImageQuad(PointD(0.0, 0.0), PointD(100.0, 0.0), PointD(100.0, 100.0), PointD(0.0, 100.0)),
            confirmedFinderPolarity = FinderPolarity.NORMAL,
        )

        assertTrue(settings.searchSummary().contains("表示中のQR格子"))
        assertTrue(settings.searchSummary().contains("V8"))
    }

    @Test fun preRecoveryEditsAreCarriedAsStableModuleCoordinates() {
        val coord = Coord(3, 7)
        val settings = PreprocessSettings(preRecoveryEdits = mapOf(coord to ModuleValue.ERASED))

        assertEquals(ModuleValue.ERASED, settings.preRecoveryEdits[coord])
    }
    private val image = LumaImage(9, 9, IntArray(81) { 127 })

    @Test fun globalThresholdCreatesBlackWhiteAndErasureRegions() {
        val profile = PreprocessSettings(
            polarity = PolarityMode.NORMAL,
            automaticThresholds = false,
            blackMax = 80,
            whiteMin = 180,
        ).variants().single()
        assertEquals(ModuleValue.ONE, profile.classify(image, 40.0, 4.0, 4.0, 1.0))
        assertEquals(ModuleValue.ERASED, profile.classify(image, 127.0, 4.0, 4.0, 1.0))
        assertEquals(ModuleValue.ZERO, profile.classify(image, 220.0, 4.0, 4.0, 1.0))
    }

    @Test fun adjacentGlobalThresholdsCannotCreateFractionalErasureBand() {
        val profile = SamplingProfile(
            PreprocessSettings(automaticThresholds = false, blackMax = 123, whiteMin = 124),
            inverted = false,
        )
        for (step in 0..20) {
            val value = 122.0 + step * 0.1
            assertTrue(profile.classify(image, value, 4.0, 4.0, 1.0) != ModuleValue.ERASED)
        }
    }

    @Test fun inversionSwapsBlackAndWhiteInterpretation() {
        val profile = PreprocessSettings(
            polarity = PolarityMode.INVERTED,
            automaticThresholds = false,
            blackMax = 80,
            whiteMin = 180,
        ).variants().single()
        assertEquals(ModuleValue.ZERO, profile.classify(image, 20.0, 4.0, 4.0, 1.0))
        assertEquals(ModuleValue.ONE, profile.classify(image, 235.0, 4.0, 4.0, 1.0))
    }

    @Test fun autoProducesBothPolarities() {
        assertEquals(listOf(false, true), PreprocessSettings(polarity = PolarityMode.AUTO).variants().map { it.inverted })
    }

    @Test fun manualBitLockControlsOnlyKnownPreRecoveryEdits() {
        val locked = PreprocessSettings(lockManualBits = true)
        val unlocked = PreprocessSettings(lockManualBits = false)
        assertEquals(ModuleSource.USER, locked.sourceForPreRecoveryEdit(ModuleValue.ONE))
        assertEquals(ModuleSource.USER, locked.sourceForPreRecoveryEdit(ModuleValue.ZERO))
        assertEquals(ModuleSource.USER_UNLOCKED, unlocked.sourceForPreRecoveryEdit(ModuleValue.ONE))
        assertEquals(ModuleSource.USER_UNLOCKED, unlocked.sourceForPreRecoveryEdit(ModuleValue.ZERO))
        assertEquals(ModuleSource.USER_SOFT, unlocked.sourceForPreRecoveryEdit(ModuleValue.ERASED))
    }

    @Test fun finderDetectionAddsLogicalWhiteFourModuleBorder() {
        val source = LumaImage(21, 21, IntArray(21 * 21) { 0 })
        val profile = PreprocessSettings(
            polarity = PolarityMode.NORMAL,
            quietZoneCorrection = true,
            version = 1,
        ).variants().single()

        val prepared = prepareFinderDetection(source, profile)

        assertEquals(4, prepared.padding)
        assertEquals(29, prepared.image.width)
        assertEquals(255, prepared.image.luma(0, 0))
        assertEquals(0, prepared.image.luma(prepared.padding, prepared.padding))
        assertTrue(prepared.toDetection(PointD(0.0, 0.0)) == PointD(4.0, 4.0))
    }

    @Test fun finderPaddingIsLogicalWhiteAfterInversion() {
        val source = LumaImage(21, 21, IntArray(21 * 21) { 255 })
        val profile = PreprocessSettings(
            polarity = PolarityMode.INVERTED,
            quietZoneCorrection = true,
            version = 1,
        ).variants().single()

        val prepared = prepareFinderDetection(source, profile)

        assertEquals(255, prepared.image.luma(0, 0))
        assertEquals(0, prepared.image.luma(prepared.padding, prepared.padding))
    }

    @Test fun manualSymbolBoundaryMasksConnectedBlackViewerBackground() {
        val source = LumaImage(31, 31, IntArray(31 * 31) { 0 })
        val profile = PreprocessSettings(
            polarity = PolarityMode.NORMAL,
            quietZoneCorrection = true,
            version = 1,
            manualQuad = ImageQuad(
                PointD(5.0, 5.0), PointD(25.0, 5.0),
                PointD(25.0, 25.0), PointD(5.0, 25.0),
            ),
        ).variants().single()

        val prepared = prepareFinderDetection(source, profile)

        assertEquals(255, prepared.image.luma(prepared.padding, prepared.padding))
        assertEquals(0, prepared.image.luma(prepared.padding + 15, prepared.padding + 15))
    }
}
