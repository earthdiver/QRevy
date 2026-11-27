package qrevy.web;
import org.teavm.jso.JSBody;
import org.teavm.jso.JSObject;
public final class Interop {
    @JSBody(params={"o"},script="delete o.nativeScanResult;")
    public static native void resetNativeScan(JSObject o);

    @JSBody(params={"o","pixels","width","height"}, script="return o.nativeScanResult || (o.nativeScanResult = typeof globalThis.qrevyNativeScan === 'function' ? globalThis.qrevyNativeScan(pixels,width,height) : {boundaries:[],decoded:null});")
    public static native JSObject nativeScan(JSObject o,JSObject pixels,int width,int height);
    @JSBody(params={"size"}, script="return new Uint8ClampedArray(size);")
    public static native JSObject bytes(int size);
    @JSBody(params={"bytes","index","argb"}, script="bytes[index]=argb>>>16&255;bytes[index+1]=argb>>>8&255;bytes[index+2]=argb&255;bytes[index+3]=255;")
    public static native void putPixel(JSObject bytes,int index,int argb);
    @JSBody(params={"width","height","bytes"}, script="return {width:width,height:height,data:bytes};")
    public static native JSObject imageResult(int width,int height,JSObject bytes);

    @JSBody(params={"o"}, script="return o && o.length === 1 && typeof Atomics !== 'undefined' && Atomics.load(o,0)!==0;")
    public static native boolean stopped(JSObject o);
    @JSBody(params={"o","key","fallback"}, script="return typeof o[key] === 'number' ? o[key] : fallback;")
    public static native double number(JSObject o,String key,double fallback);
    @JSBody(params={"o","key","fallback"}, script="return typeof o[key] === 'string' ? o[key] : fallback;")
    public static native String string(JSObject o,String key,String fallback);
    @JSBody(params={"o","key","fallback"}, script="return typeof o[key] === 'boolean' ? o[key] : fallback;")
    public static native boolean bool(JSObject o,String key,boolean fallback);
    @JSBody(params={"o","key"}, script="return o[key] || {};")
    public static native JSObject object(JSObject o,String key);
    @JSBody(params={"o","i"}, script="return o[i];")
    public static native double item(JSObject o,int i);
    @JSBody(params={"o"}, script="return o.length || 0;")
    public static native int length(JSObject o);
    @JSBody(params={"text"}, script="return JSON.parse(text);")
    public static native JSObject parse(String text);
    @JSBody(params={"text"}, script="if (typeof postMessage === 'function' && typeof document === 'undefined') postMessage({progress:text});")
    public static native void progress(String text);
}
