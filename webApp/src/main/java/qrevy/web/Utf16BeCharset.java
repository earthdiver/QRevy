package qrevy.web;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.*;

/** Decode complete surrogate pairs even when they end the QR byte segment. */
final class Utf16BeCharset extends Charset {
    Utf16BeCharset() { super("UTF-16BE", new String[]{"UnicodeBigUnmarked"}); }
    public boolean contains(Charset other) { return StandardCharsets.UTF_16BE.contains(other); }
    public CharsetEncoder newEncoder() { return StandardCharsets.UTF_16BE.newEncoder(); }
    public CharsetDecoder newDecoder() {
        return new CharsetDecoder(this, 1, 1) {
            protected CoderResult decodeLoop(ByteBuffer in, CharBuffer out) {
                while (in.hasRemaining()) {
                    int pos = in.position();
                    if (in.remaining() < 2) return CoderResult.UNDERFLOW;
                    char first = (char)(((in.get(pos) & 255) << 8) | (in.get(pos + 1) & 255));
                    if (Character.isLowSurrogate(first)) return CoderResult.malformedForLength(2);
                    if (Character.isHighSurrogate(first)) {
                        if (in.remaining() < 4) return CoderResult.UNDERFLOW;
                        char second = (char)(((in.get(pos + 2) & 255) << 8) | (in.get(pos + 3) & 255));
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
