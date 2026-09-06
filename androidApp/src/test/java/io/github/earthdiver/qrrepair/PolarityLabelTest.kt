package io.github.earthdiver.qrrepair

import io.github.earthdiver.qrrepair.core.PolarityMode
import org.junit.Assert.assertEquals
import org.junit.Test

class PolarityLabelTest {
    @Test fun invertedModeLabelsDarkPixelsAsWhiteAndLightPixelsAsBlack() {
        assertEquals("QRの白(0)" to "QRの黒(1)", polarityThresholdMeanings(PolarityMode.INVERTED))
    }

    @Test fun normalModeLabelsDarkPixelsAsBlackAndLightPixelsAsWhite() {
        assertEquals("QRの黒(1)" to "QRの白(0)", polarityThresholdMeanings(PolarityMode.NORMAL))
    }
}
