import java.nio.*;import java.nio.charset.*;import java.nio.file.*;import java.util.*;
public class GenerateGbRanges {
 public static void main(String[]args)throws Exception{
  CharsetDecoder decoder=Charset.forName("GB18030").newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
  ArrayList<int[]> ranges=new ArrayList<>();int start=-1,unicode=-1,previous=-2,last=-2;
  byte[] bytes=new byte[4];
  for(int pointer=0;pointer<126*10*126*10;pointer++){
   int p=pointer;bytes[3]=(byte)(p%10+48);p/=10;bytes[2]=(byte)(p%126+129);p/=126;bytes[1]=(byte)(p%10+48);bytes[0]=(byte)(p/10+129);int cp=-1;
   try{String s=decoder.decode(ByteBuffer.wrap(bytes)).toString();if(s.codePointCount(0,s.length())==1)cp=s.codePointAt(0);}catch(CharacterCodingException e){}decoder.reset();
   if(cp>=0){if(start>=0&&cp==previous+1&&pointer==last+1){previous=cp;last=pointer;}else{if(start>=0)ranges.add(new int[]{start,unicode,last-start+1});start=pointer;unicode=cp;previous=cp;last=pointer;}}
   else if(start>=0){ranges.add(new int[]{start,unicode,last-start+1});start=-1;}
  }
  if(start>=0)ranges.add(new int[]{start,unicode,last-start+1});StringBuilder s=new StringBuilder("package qrevy.web;\n// Generated from OpenJDK GB18030 four-byte mappings.\nfinal class GbRanges { static final int[] DATA={\n");for(int[]r:ranges)s.append(r[0]+","+r[1]+","+r[2]+",\n");s.append("}; }\n");Files.writeString(Path.of("src/main/java/qrevy/web/GbRanges.java"),s);System.out.println(ranges.size()+" ranges");
 }
}
