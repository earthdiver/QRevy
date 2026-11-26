package qrevy.web;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.*;

/** UTF-16 decoding with BOM detection and complete trailing surrogate pairs. */
final class Utf16Charset extends Charset {
    Utf16Charset(String name) { super(name, new String[0]); }
    public boolean contains(Charset other) { return StandardCharsets.UTF_16.contains(other); }
    public CharsetEncoder newEncoder() { return Charset.forName(name()).newEncoder(); }
    public CharsetDecoder newDecoder() {
        return new CharsetDecoder(this, 1, 1) {
            // Zero means that a BOM may still set the byte order; otherwise 1=BE, 2=LE.
            private int order = initialOrder();
            private int initialOrder() { return name().equals("UTF-16LE") ? 2 : name().equals("UTF-16BE") ? 1 : 0; }
            protected void implReset() { order = initialOrder(); }
            private char unit(ByteBuffer in, int pos) {
                int a = in.get(pos) & 255, b = in.get(pos + 1) & 255;
                return (char)(order == 2 ? (b << 8) | a : (a << 8) | b);
            }
            protected CoderResult decodeLoop(ByteBuffer in, CharBuffer out) {
                while (in.hasRemaining()) {
                    int pos = in.position();
                    if (in.remaining() < 2) return CoderResult.UNDERFLOW;
                    if (order == 0) {
                        char marker = unit(in, pos);
                        order = marker == '\uFFFE' ? 2 : 1;
                        if (marker == '\uFEFF' || marker == '\uFFFE') {
                            in.position(pos + 2);
                            continue;
                        }
                    }
                    char first = unit(in, pos);
                    if (Character.isLowSurrogate(first)) return CoderResult.malformedForLength(2);
                    if (Character.isHighSurrogate(first)) {
                        if (in.remaining() < 4) return CoderResult.UNDERFLOW;
                        char second = unit(in, pos + 2);
                        if (!Character.isLowSurrogate(second)) return CoderResult.malformedForLength(4);
                        if (out.remaining() < 2) return CoderResult.OVERFLOW;
                        out.put(first); out.put(second);
                        in.position(pos + 4);
                    } else {
                        if (!out.hasRemaining()) return CoderResult.OVERFLOW;
                        out.put(first);
                        in.position(pos + 2);
                    }
                }
                return CoderResult.UNDERFLOW;
            }
        };
    }
}
