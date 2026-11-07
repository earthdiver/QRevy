package com.google.zxing.qrcode.decoder;

import com.google.zxing.DecodeHintType;
import com.google.zxing.FormatException;
import com.google.zxing.common.DecoderResult;
import com.google.zxing.common.BitSource;

import java.util.Map;

import io.github.earthdiver.qrrepair.core.QrPayloadTextDecoder;

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
    // ZXing decodes each compaction segment separately. ECI applies to the expanded
    // byte stream, and an assumed UTF-8 character can cross consecutive Byte segments.
    boolean assumeUtf8 = hints != null && "UTF-8".equalsIgnoreCase(String.valueOf(hints.get(DecodeHintType.CHARACTER_SET)));
    String text = decoded.getSymbologyModifier() % 2 == 0 || assumeUtf8
        ? QrPayloadTextDecoder.decode(data, versionNumber, hints) : null;
    if (indicatorOffset < 0 && text == null) return decoded;
    return new DecoderResult(data, text == null ? decoded.getText() : text, decoded.getByteSegments(), decoded.getECLevel(),
        decoded.getStructuredAppendSequenceNumber(), decoded.getStructuredAppendParity(),
        decoded.getSymbologyModifier());
  }

  /** Raw application indicator, retained separately from the decoded body. */
  public static Integer fnc1ApplicationIndicator(byte[] data) throws FormatException {
    int offset = fnc1IndicatorOffset(data);
    if (offset < 0) return null;
    BitSource bits = new BitSource(data);
    while (offset > 0) {
      int width = Math.min(offset, 32);
      bits.readBits(width);
      offset -= width;
    }
    return bits.readBits(8);
  }

  private static int fnc1IndicatorOffset(byte[] data) throws FormatException {
    BitSource bits = new BitSource(data);
    boolean fnc1Seen = false;
    try {
      while (bits.available() >= 4) {
        int mode = bits.readBits(4);
        switch (mode) {
          case 3: // Structured Append must be the first header.
            if (bits.getByteOffset() != 0 || bits.getBitOffset() != 4) throw FormatException.getFormatInstance();
            bits.readBits(16);
            break;
          case 7: // ECI may precede FNC1; skip its variable-width designator.
            int first = bits.readBits(8);
            if ((first & 0x80) == 0) break;
            if ((first & 0xc0) == 0x80) bits.readBits(8);
            else if ((first & 0xe0) == 0xc0) bits.readBits(16);
            else throw FormatException.getFormatInstance();
            break;
          case 5:
            if (fnc1Seen) throw FormatException.getFormatInstance();
            fnc1Seen = true;
            break;
          case 9:
            if (fnc1Seen) throw FormatException.getFormatInstance();
            int offset = bits.getByteOffset() * 8 + bits.getBitOffset();
            int indicator = bits.readBits(8);
            if (!(indicator < 100 || indicator >= 165 && indicator <= 190 || indicator >= 197 && indicator <= 222)) {
              throw FormatException.getFormatInstance();
            }
            return offset;
          default:
            return -1;
        }
      }
      return -1;
    } catch (IllegalArgumentException exception) {
      throw FormatException.getFormatInstance(exception);
    }
  }
}
