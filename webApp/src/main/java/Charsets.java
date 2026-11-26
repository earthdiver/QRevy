package qrevy.web;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;
/** Exact JVM single/double-byte mappings, including strict Kanji/Hanzi validation. */
public final class Charsets {
    private static final Map<String,Charset> cache=new HashMap<>();
    public static boolean isSupported(String name) { try { forName(name); return true; } catch (Exception e) { return false; } }
    public static Charset forName(String name) {
        String n=name.toUpperCase(Locale.ROOT).replace("_","-");
        switch(n) {
            case "UTF8": n="UTF-8"; break;
            case "ASCII": n="US-ASCII"; break;
            case "UNICODEBIGUNMARKED": case "UNICODEBIG": n="UTF-16BE"; break;
            case "SJIS": case "SHIFT-JIS": n="Shift_JIS"; break;
            case "EUC-CN": n="GB2312"; break;
            case "CP437": n="Cp437"; break;
            case "CP1250": case "CP1251": case "CP1252": case "CP1256": n="windows-"+n.substring(2); break;
            default: if(n.startsWith("ISO8859-"))n="ISO-8859-"+n.substring(8);
                else if(n.startsWith("WINDOWS-")) n="windows-"+n.substring(8);
                else if(n.equals("BIG5"))n="Big5";
        }
        Charset known=cache.get(n); if(known!=null)return known;
        String table=CharsetTables.table(n);
        Charset result=n.equals("UTF-8")?new Utf8Charset():n.equals("UTF-16BE")?new Utf16BeCharset():n.equals("UTF-16")||n.equals("UTF-16LE")?new Utf16Charset(n):table==null?Charset.forName(n):new Mapped(n,table);
        cache.put(n,result);return result;
    }
    private static final class Mapped extends Charset {
        final char[] table; final boolean[] leads=new boolean[256]; int[] reverse;
        Mapped(String name,String data) {
            super(name,new String[0]);byte[] bytes=Base64.getDecoder().decode(data);table=new char[bytes.length/2];
            for(int i=0;i<table.length;i++) table[i]=(char)(((bytes[2*i]&255)<<8)|(bytes[2*i+1]&255));
            for(int i=256;i<table.length;i++)if(table[i]!='\uffff')leads[(i-256)/256]=true;
        }
        public boolean contains(Charset c) {return c.name().equals(name()) || c.name().equals("US-ASCII");}
        public CharsetDecoder newDecoder() {return new CharsetDecoder(this,1,1) {
            protected CoderResult decodeLoop(ByteBuffer in,CharBuffer out) {
                while(in.hasRemaining()) {
                    int pos=in.position(),a=in.get(pos)&255;char c=table[a];int count=1;
                    if(c=='\uffff') {
                        if(table.length==256)return CoderResult.unmappableForLength(1);
                        if(name().equals("GB18030") && (a<129||a>254))return CoderResult.malformedForLength(1);
                        if(in.remaining()<2)return CoderResult.UNDERFLOW;
                        c=table[256+a*256+(in.get(pos+1)&255)];count=2;
                        if(c=='\uffff' && name().equals("GB18030") && (in.get(pos+1)&255)>=48 && (in.get(pos+1)&255)<=57) {
                            if(in.remaining()<3)return CoderResult.UNDERFLOW;
                            int b=in.get(pos+1)&255,d=in.get(pos+2)&255;
                            if(d<129||d>254)return CoderResult.malformedForLength(3);
                            if(in.remaining()<4)return CoderResult.UNDERFLOW;
                            int e=in.get(pos+3)&255;
                            if(e<48||e>57)return CoderResult.malformedForLength(4);
                            int pointer=((a-129)*10+b-48)*1260+(d-129)*10+e-48,cp=-1;
                            for(int i=0;i<GbRanges.DATA.length;i+=3)if(pointer>=GbRanges.DATA[i]&&pointer<GbRanges.DATA[i]+GbRanges.DATA[i+2]){cp=GbRanges.DATA[i+1]+pointer-GbRanges.DATA[i];break;}
                            if(cp<0)return CoderResult.malformedForLength(4);
                            int length=cp>65535?2:1;if(out.remaining()<length)return CoderResult.OVERFLOW;
                            if(length==2){out.put(Character.highSurrogate(cp));out.put(Character.lowSurrogate(cp));}else out.put((char)cp);
                            in.position(pos+4);continue;
                        }
                        if(c=='\uffff') {
                            int b=in.get(pos+1)&255;
                            if(name().equals("GB18030"))return CoderResult.malformedForLength(b<48?1:2);
                            // A valid standalone character or a new lead byte belongs
                            // to the next character, not to this malformed sequence.
                            if(!leads[a]||leads[b]||table[b]!='\uffff')return CoderResult.malformedForLength(1);
                            return CoderResult.unmappableForLength(2);
                        }
                    }
                    if(!out.hasRemaining())return CoderResult.OVERFLOW;
                    out.put(c);in.position(pos+count);
                }
                return CoderResult.UNDERFLOW;
            }
        };}
        public CharsetEncoder newEncoder() {
            if(reverse==null) {reverse=new int[65536];Arrays.fill(reverse,-1);for(int i=table.length-1;i>=0;i--)if(table[i]!='\uffff')reverse[table[i]]=i;}
            return new CharsetEncoder(this,2,4) {
                protected CoderResult encodeLoop(CharBuffer in,ByteBuffer out) {
                    while(in.hasRemaining()) {
                        char c=in.get(in.position());int n=reverse[c];
                        if(n<0 && name().equals("GB18030")) {
                            int cp=c,chars=1;
                            if(Character.isHighSurrogate(c)){if(in.remaining()<2)return CoderResult.UNDERFLOW;char low=in.get(in.position()+1);if(!Character.isLowSurrogate(low))return CoderResult.malformedForLength(1);cp=Character.toCodePoint(c,low);chars=2;}
                            else if(Character.isLowSurrogate(c))return CoderResult.malformedForLength(1);
                            int pointer=-1;for(int i=0;i<GbRanges.DATA.length;i+=3)if(cp>=GbRanges.DATA[i+1]&&cp<GbRanges.DATA[i+1]+GbRanges.DATA[i+2]){pointer=GbRanges.DATA[i]+cp-GbRanges.DATA[i+1];break;}
                            if(pointer<0)return CoderResult.unmappableForLength(chars);if(out.remaining()<4)return CoderResult.OVERFLOW;
                            int d=pointer%10+48;pointer/=10;int b=pointer%126+129;pointer/=126;int a=pointer%10+48;int first=pointer/10+129;
                            out.put((byte)first);out.put((byte)a);out.put((byte)b);out.put((byte)d);in.position(in.position()+chars);continue;
                        }
                        if(n<0)return CoderResult.unmappableForLength(1);
                        int size=n<256?1:2;if(out.remaining()<size)return CoderResult.OVERFLOW;
                        if(size==1)out.put((byte)n);else {out.put((byte)((n-256)>>8));out.put((byte)(n-256));}in.get();
                    }return CoderResult.UNDERFLOW;
                }
            };
        }
    }
}
