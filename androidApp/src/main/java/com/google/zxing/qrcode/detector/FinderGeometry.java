package com.google.zxing.qrcode.detector;

import com.google.zxing.DecodeHintType;
import com.google.zxing.NotFoundException;
import com.google.zxing.common.BitMatrix;
import java.util.Collections;

/** Retain observed Finder centers even when sampling a damaged QR fails. */
public final class FinderGeometry {
    private FinderGeometry() {}
    public static FinderPatternInfo find(BitMatrix image) throws NotFoundException {
        return new FinderPatternFinder(image).find(Collections.singletonMap(DecodeHintType.TRY_HARDER, true));
    }
}
