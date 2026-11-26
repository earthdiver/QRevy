import java.nio.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
public class GenerateCharsets {
 public static void main(String[] args) throws Exception {
  String[] names={"Cp437","ISO-8859-2","ISO-8859-3","ISO-8859-4","ISO-8859-5","ISO-8859-6","ISO-8859-7","ISO-8859-8","ISO-8859-9","ISO-8859-11","ISO-8859-13","ISO-8859-15","ISO-8859-16","Shift_JIS","windows-1250","windows-1251","windows-1252","windows-1256","Big5","GB2312","GB18030","GBK","EUC-KR"};
  StringBuilder code=new StringBuilder("package qrevy.web;\n// Generated from OpenJDK charset mappings by scripts/GenerateCharsets.java.\nfinal class CharsetTables {\n static String table(String name) { switch(name) {\n");
  int index=0;
  for(String name:names) {
   CharsetDecoder decoder=Charset.forName(name).newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
   boolean multi=Set.of("Shift_JIS","Big5","GB2312","GB18030","GBK","EUC-KR").contains(name);
   char[] chars=new char[multi?65792:256]; Arrays.fill(chars,'\uffff');
   for(int i=0;i<chars.length;i++) {
    byte[] b=i<256?new byte[]{(byte)i}:new byte[]{(byte)((i-256)>>8),(byte)(i-256)};
    try { CharBuffer c=decoder.decode(ByteBuffer.wrap(b)); if(c.length()==1) chars[i]=c.charAt(0); } catch(CharacterCodingException e) {} decoder.reset();
   }
   ByteBuffer bytes=ByteBuffer.allocate(chars.length*2); for(char c:chars)bytes.putChar(c);
   String b64=Base64.getEncoder().encodeToString(bytes.array());String id="T"+(index++);
   StringBuilder cls=new StringBuilder("package qrevy.web;\nfinal class "+id+" { static String data() { return String.join(\"\",\n");
   for(int i=0;i<b64.length();i+=16000) { if(i>0)cls.append(",\n");cls.append('"').append(b64.substring(i,Math.min(i+16000,b64.length()))).append('"'); }
   cls.append("); } }\n");Files.writeString(Path.of("src/main/java/qrevy/web/"+id+".java"),cls);
   code.append("case \"").append(name).append("\": return ").append(id).append(".data();\n");
  }
  code.append("default: return null; } } }\n");Files.writeString(Path.of("src/main/java/qrevy/web/CharsetTables.java"),code);
 }
}
