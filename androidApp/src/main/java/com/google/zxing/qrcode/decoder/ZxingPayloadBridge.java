package com.google.zxing.qrcode.decoder;

import com.google.zxing.DecodeHintType;
import com.google.zxing.FormatException;
import com.google.zxing.common.DecoderResult;
import com.google.zxing.common.BitSource;

import java.util.Map;

/**
 * Small package bridge: DecodedBitStreamParser is intentionally package-private in ZXing.
 * This keeps QR payload parsing aligned with ZXing after our custom errors+erasures RS stage.
 */
public final class ZxingPayloadBridge {
  private ZxingPayloadBridge() {}

  public static DecoderResult decodeData(
      byte[] data,
      int versionNumber,
      ErrorCorrectionLevel ecLevel,
      Map<DecodeHintType, ?> hints) throws FormatException {
    // ZXing 3.5.4 recognizes FNC1 second position but does not consume its application
    // indicator. Remove only that field from a private decoding view. Validation, RS and
    // reported raw bytes must continue to describe the original stream.
    int indicatorOffset = fnc1IndicatorOffset(data);
    byte[] decodeView = data;
    if (indicatorOffset >= 0) {
      decodeView = new byte[data.length - 1];
      for (int bit = 0; bit < decodeView.length * 8; bit++) {
        int originalBit = bit < indicatorOffset ? bit : bit + 8;
        int value = (data[originalBit / 8] >>> (7 - originalBit % 8)) & 1;
        decodeView[bit / 8] |= (byte) (value << (7 - bit % 8));
      }
    }
    DecoderResult decoded = DecodedBitStreamParser.decode(
        decodeView,
        Version.getVersionForNumber(versionNumber),
        ecLevel,
        hints);
    if (indicatorOffset < 0) return decoded;
    return new DecoderResult(data, decoded.getText(), decoded.getByteSegments(), decoded.getECLevel(),
        decoded.getStructuredAppendSequenceNumber(), decoded.getStructuredAppendParity(),
        decoded.getSymbologyModifier());
  }

  /** Raw application indicator, retained separately from the decoded body. */
  public static Integer fnc1ApplicationIndicator(byte[] data) throws FormatException {
    int offset = fnc1IndicatorOffset(data);
    if (offset < 0) return null;
    BitSource bits = new BitSource(data);
    bits.readBits(offset);
    return bits.readBits(8);
  }

  private static int fnc1IndicatorOffset(byte[] data) throws FormatException {
    BitSource bits = new BitSource(data);
    if (bits.available() < 4) return -1;
    int mode = bits.readBits(4);
    int offset = 4;
    if (mode == 3) { // Structured Append may precede FNC1.
      if (bits.available() < 20) throw FormatException.getFormatInstance();
      bits.readBits(16);
      mode = bits.readBits(4);
      offset = 24;
    }
    if (mode != 9) return -1;
    if (bits.available() < 8) throw FormatException.getFormatInstance();
    return offset;
  }
}
