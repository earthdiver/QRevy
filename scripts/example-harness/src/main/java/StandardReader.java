import com.google.gson.*;
import com.google.zxing.*;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import javax.imageio.ImageIO;
import java.io.*;
import java.util.*;

/** JSON-lines image decoder; never uses repair, expected text, or manual geometry. */
public class StandardReader {
 public static void main(String[] args) throws Exception {
  var input=new BufferedReader(new InputStreamReader(System.in));
  for(String line;(line=input.readLine())!=null;) {
   var out=new JsonObject();
   try {
    String path=JsonParser.parseString(line).getAsJsonObject().get("path").getAsString();
    var image=ImageIO.read(new File(path));
    if(image==null)throw new IOException("Unsupported raster");
    var rgb=image.getRGB(0,0,image.getWidth(),image.getHeight(),null,0,image.getWidth());
    boolean decoded=false;
    for(boolean harder:new boolean[]{false,true}) {
     try {
      var bitmap=new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(image.getWidth(),image.getHeight(),rgb)));
      var hints=new EnumMap<DecodeHintType,Object>(DecodeHintType.class);
      if(harder)hints.put(DecodeHintType.TRY_HARDER,true);
      new QRCodeReader().decode(bitmap,hints);
      out.addProperty("mode",harder?"TRY_HARDER":"default");decoded=true;break;
     } catch(ReaderException ignored) {}
    }
    out.addProperty("decoded",decoded);
   } catch(Exception e) {out.addProperty("error",e.toString());}
   System.out.println(out);System.out.flush();
  }
 }
}
