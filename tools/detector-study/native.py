"""Native adapters. Timing excludes disk I/O, includes API calls/output copying."""
import argparse, ctypes as C, json, statistics, time
from pathlib import Path
import cv2
import numpy as np
import zxingcpp

class Point(C.Structure): _fields_=[('x',C.c_int),('y',C.c_int)]
class Code(C.Structure):
    _fields_=[('corners',Point*4),('size',C.c_int),('bitmap',C.c_uint8*((177*177+7)//8))]

class Quirc:
    def __init__(self,path):
        self.lib=C.CDLL(str(path)); l=self.lib
        l.quirc_new.restype=C.c_void_p
        l.quirc_resize.argtypes=[C.c_void_p,C.c_int,C.c_int]; l.quirc_resize.restype=C.c_int
        l.quirc_begin.argtypes=[C.c_void_p,C.c_void_p,C.c_void_p]; l.quirc_begin.restype=C.c_void_p
        l.quirc_end.argtypes=[C.c_void_p]; l.quirc_count.argtypes=[C.c_void_p]; l.quirc_count.restype=C.c_int
        l.quirc_extract.argtypes=[C.c_void_p,C.c_int,C.POINTER(Code)]
        l.quirc_destroy.argtypes=[C.c_void_p]
        self.q=l.quirc_new()
        if not self.q: raise MemoryError('quirc_new')
    def __call__(self,img):
        l=self.lib; h,w=img.shape
        if l.quirc_resize(self.q,w,h): raise MemoryError('quirc_resize')
        dst=l.quirc_begin(self.q,None,None); C.memmove(dst,img.ctypes.data,w*h); l.quirc_end(self.q)
        out=[]
        for i in range(l.quirc_count(self.q)):
            code=Code(); l.quirc_extract(self.q,i,C.byref(code))
            out.append(dict(quad=[[p.x,p.y] for p in code.corners],n=code.size,stage='extracted'))
        return out
    def close(self): self.lib.quirc_destroy(self.q)

def main(args):
    cv2.setNumThreads(1)
    manifest=json.loads((args.corpus/'manifest.json').read_text())
    if args.engine=='quirc': detector=Quirc(args.quirc)
    elif args.engine=='zxing_cpp':
        def detector(im):
            out=[]
            for b in zxingcpp.read_barcodes(im,formats=zxingcpp.BarcodeFormat.QRCode,return_errors=True):
                p=b.position
                out.append(dict(quad=[[v.x,v.y] for v in [p.top_left,p.top_right,p.bottom_right,p.bottom_left]],
                                n=(17+4*int(b.extra['Version'])) if str(b.extra.get('Version','')).isdigit() else None,stage='decoded' if b.valid else str(b.error)))
            return out
    else:
        obj=cv2.QRCodeDetectorAruco() if args.engine=='opencv_aruco' else cv2.QRCodeDetector()
        def detector(im):
            ok,points=obj.detectMulti(im)
            return [dict(quad=q.tolist(),n=None,stage='detected') for q in points] if ok else []
    clean=cv2.imread(str(args.corpus/'v1-s0-clean.png'),0)
    for _ in range(10): detector(clean)
    with args.out.open('w') as f:
        for idx,c in enumerate(manifest):
            im=cv2.imread(str(args.corpus/c['path']),0); times=[]; candidates=[]
            for _ in range(args.repeats):
                t=time.perf_counter_ns(); candidates=detector(im); times.append((time.perf_counter_ns()-t)/1e6)
            row=dict(id=c['id'],engine=args.engine,ms=statistics.median(times),times=times,candidates=candidates)
            f.write(json.dumps(row)+'\n'); f.flush()
            if idx%40==0: print(args.engine,idx,len(manifest),flush=True)
    if isinstance(detector,Quirc): detector.close()

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('engine',choices=['quirc','zxing_cpp','opencv','opencv_aruco'])
    p.add_argument('corpus',type=Path);p.add_argument('out',type=Path)
    p.add_argument('--quirc',type=Path,required=True)
    p.add_argument('--repeats',type=int,default=3);main(p.parse_args())
