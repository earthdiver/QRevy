package io.github.earthdiver.qrrepair

import io.github.earthdiver.qrrepair.core.*
import org.junit.Assert.assertEquals
import org.junit.Test

class PolarityLabelTest {
    @Test fun invertedLabelsUseTheSourceImageBoundariesAndCorrectSliderRoles() {
        assertEquals("画像の明部（QRの黒(1)）: 175以上" to "画像の暗部（QRの白(0)）: 75以下",
            polarityThresholdLabels(true, 80, 180))
        val profile = SamplingProfile(PreprocessSettings(blackMax = 80, whiteMin = 180), true)
        assertEquals(ModuleValue.ONE, profile.classifyLuminance(profile.normalize(175.0)))
        assertEquals(ModuleValue.ZERO, profile.classifyLuminance(profile.normalize(75.0)))
        assertEquals(ModuleValue.ERASED, profile.classifyLuminance(profile.normalize(80.0)))
        assertEquals("Light pixels (QR black (1)): ≥ 175" to "Dark pixels (QR white (0)): ≤ 75",
            polarityThresholdLabels(true, 80, 180, false))
    }

    @Test fun normalLabelsKeepSourceImageThresholds() {
        assertEquals("画像の暗部（QRの黒(1)）: 80以下" to "画像の明部（QRの白(0)）: 180以上",
            polarityThresholdLabels(false, 80, 180))
    }
}
