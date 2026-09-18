"""Deterministic, synthetic-only QR images and ground truth. No attached images."""
import argparse, hashlib, json
from pathlib import Path
import cv2
import numpy as np
import qrcode
from PIL import Image

CONDITIONS = ['clean', 'rotate17', 'rotate43', 'perspective_mild', 'perspective_strong',
              'blur_mild', 'blur_strong', 'shadow', 'low_contrast', 'small_modules',
              'no_quiet_zone', 'clutter', 'one_finder_missing', 'two_finders_missing',
              'three_finders_missing', 'finder_ring_damage', 'gray_data_patch',
              'white_data_patch', 'scratch', 'combined']

def make_corpus(out):
    out.mkdir(parents=True, exist_ok=True)
    records = []
    for version in [1, 3, 7]:
        for seed in range(4):
            text = f'Q{version}S{seed}TEST'
            qr = qrcode.QRCode(version=version, error_correction=qrcode.constants.ERROR_CORRECT_H,
                               box_size=1, border=0, mask_pattern=(version + seed) % 8)
            qr.add_data(text, optimize=0); qr.make(fit=False)
            bits = np.array(qr.get_matrix(), np.uint8)
            n = len(bits)
            for condition in CONDITIONS:
                rng = np.random.default_rng(17000 + 100 * version + seed)
                pitch = 2 if condition == 'small_modules' else 7
                cells = ((1-bits)*255).astype(np.uint8)
                corners = [(0,0), (n-7,0), (0,n-7)]
                if 'finders_missing' in condition or condition == 'one_finder_missing':
                    count = {'one_finder_missing':1,'two_finders_missing':2,'three_finders_missing':3}[condition]
                    for idx in [(seed+i)%3 for i in range(count)]:
                        x,y = corners[idx]; cells[y:y+7,x:x+7] = 255
                if condition == 'finder_ring_damage':
                    for x,y in corners:
                        cells[y:y+2,x:x+7] = 255
                if condition in ['gray_data_patch','white_data_patch']:
                    k = max(3, int(n*.22)); a = n//2-k//2
                    cells[a:a+k,a:a+k] = 127 if condition == 'gray_data_patch' else 255
                src_img = np.repeat(np.repeat(cells,pitch,axis=0),pitch,axis=1)
                side = n*pitch
                src = np.float32([[-.5,-.5],[side-.5,-.5],[side-.5,side-.5],[-.5,side-.5]])
                dst = np.float32([[-side/2,-side/2],[side/2,-side/2],[side/2,side/2],[-side/2,side/2]])
                if condition.startswith('perspective') or condition == 'combined':
                    strength = .23 if condition == 'perspective_strong' else .10
                    dst += np.float32([[.4,1],[-.3,-.7],[-1,.4],[.7,-.2]]) * side*strength
                angle = {'rotate17':17,'rotate43':43,'combined':17}.get(condition,0)
                angle += seed*90  # Every category covers all four logical orientations.
                a = np.deg2rad(angle); rot = np.array([[np.cos(a),-np.sin(a)],[np.sin(a),np.cos(a)]])
                dst = (dst @ rot.T + 255.5).astype(np.float32)
                H = cv2.getPerspectiveTransform(src,dst)
                img = cv2.warpPerspective(src_img,H,(512,512),flags=cv2.INTER_LINEAR,borderValue=255)
                if condition == 'no_quiet_zone':
                    x,y = np.floor(dst.min(axis=0)+.5).astype(int)
                    img = img[y:y+side,x:x+side].copy(); dst -= [x,y]
                if condition == 'clutter':
                    cv2.putText(img,'SYNTHETIC TEST',(10,30),cv2.FONT_HERSHEY_SIMPLEX,.6,0,2)
                    for i in range(8):
                        x=int(rng.integers(5,65)); y=int(rng.integers(55,450))
                        cv2.rectangle(img,(x,y),(x+20,y+15),0,2)
                if condition in ['blur_mild','blur_strong','combined']:
                    sigma = pitch * (.25 if condition == 'blur_strong' else .13)
                    img = cv2.GaussianBlur(img,(0,0),sigma)
                if condition in ['shadow','combined']:
                    yy,xx=np.mgrid[:img.shape[0],:img.shape[1]]
                    factor = .27 + .73*xx/max(1,img.shape[1]-1)
                    img=np.uint8(np.clip(img*factor,0,255))
                if condition == 'low_contrast': img=np.uint8(105+img.astype(float)*.18)
                if condition == 'scratch':
                    cv2.line(img,tuple(np.int32(dst[0]*.6+dst[3]*.4)),tuple(np.int32(dst[1]*.4+dst[2]*.6)),255,3)
                cid=f'v{version}-s{seed}-{condition}'
                path=out/f'{cid}.png'; Image.fromarray(img).save(path)
                records.append(dict(id=cid,category=condition,path=path.name,positive=True,
                                    version=version,n=n,payload=text,quad=dst.tolist(),bits=bits.tolist(),
                                    sha256=hashlib.sha256(path.read_bytes()).hexdigest()))
    for seed in range(24):
        rng=np.random.default_rng(88000+seed)
        img=np.full((512,512),255,np.uint8)
        category=['negative_blank','negative_noise','negative_checker','negative_text'][seed%4]
        if category=='negative_noise': img=rng.integers(0,256,(512,512),dtype=np.uint8)
        elif category=='negative_checker':
            yy,xx=np.mgrid[:512,:512]; img=np.uint8(((xx//(6+seed)+yy//(6+seed))%2)*255)
        elif category=='negative_text':
            for y in range(30,490,45): cv2.putText(img,f'TEST {seed} 123456',(15,y),cv2.FONT_HERSHEY_SIMPLEX,1,0,2)
        cid=f'negative-{seed:02d}'; path=out/f'{cid}.png'; Image.fromarray(img).save(path)
        records.append(dict(id=cid,category=category,path=path.name,positive=False,
                            sha256=hashlib.sha256(path.read_bytes()).hexdigest()))
    (out/'manifest.json').write_text(json.dumps(records,indent=2)+'\n')
    print(f'{len(records)} cases: {sum(r["positive"] for r in records)} positive')

if __name__=='__main__':
    p=argparse.ArgumentParser(); p.add_argument('out',type=Path)
    make_corpus(p.parse_args().out)
