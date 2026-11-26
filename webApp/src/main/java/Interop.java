package qrevy.web;
import org.teavm.jso.JSBody;
import org.teavm.jso.JSObject;
public final class Interop {
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
