package qrevy.web;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.*;

/** UTF-8 replacement must not consume a valid character after malformed bytes. */
final class Utf8Charset extends Charset {
    Utf8Charset() { super("UTF-8", new String[]{"UTF8"}); }
    public boolean contains(Charset other) {
        return other.name().equals("UTF-8") || other.name().equals("US-ASCII");
    }
    public CharsetEncoder newEncoder() { return StandardCharsets.UTF_8.newEncoder(); }
    public CharsetDecoder newDecoder() {
        // Leave spare output capacity for TeaVM's end-of-input replacement path.
        return new CharsetDecoder(this, 2, 2) {
            protected CoderResult decodeLoop(ByteBuffer in, CharBuffer out) {
                while (in.hasRemaining()) {
                    int pos = in.position(), first = in.get(pos) & 255;
                    int length = first < 128 ? 1 : first >= 194 && first <= 223 ? 2 :
                        first >= 224 && first <= 239 ? 3 : first >= 240 && first <= 244 ? 4 : 0;
                    if (length == 0) return CoderResult.malformedForLength(1);
                    int value = first & (length == 1 ? 127 : length == 2 ? 31 : length == 3 ? 15 : 7);
                    for (int i = 1; i < length; i++) {
                        if (in.remaining() <= i) return CoderResult.UNDERFLOW;
                        int next = in.get(pos + i) & 255;
                        if ((next & 192) != 128) return CoderResult.malformedForLength(i);
                        if (i == 1 && (first == 224 && next < 160 || first == 240 && next < 144 ||
                            first == 244 && next > 143)) return CoderResult.malformedForLength(1);
                        value = (value << 6) | (next & 63);
                    }
                    if (value >= 0xD800 && value <= 0xDFFF) return CoderResult.malformedForLength(3);
                    int chars = value > 65535 ? 2 : 1;
                    if (out.remaining() < chars) return CoderResult.OVERFLOW;
                    if (chars == 2) { out.put(Character.highSurrogate(value)); out.put(Character.lowSurrogate(value)); }
                    else out.put((char)value);
                    in.position(pos + length);
                }
                return CoderResult.UNDERFLOW;
            }
        };
    }
}
