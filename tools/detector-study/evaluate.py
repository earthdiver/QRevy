"""Truth is used here only, never by a detector or recovery candidate ranking."""
import argparse, csv, json
from pathlib import Path
import cv2
import numpy as np

ENGINES=['baseline','zxing_java','quirc','boofcv','zxing_cpp','opencv','opencv_aruco','oracle']

def geometry_error(quad,truth):
    """RMS of a 9x9 normalized grid, minimized over four rotations, in modules."""
    q=np.asarray(quad,np.float32);gt=np.asarray(truth['quad'],np.float32)
    if q.shape!=(4,2) or not np.isfinite(q).all():return float('inf')
    unit=np.float32([[0,0],[1,0],[1,1],[0,1]])
    coords=np.float32([(x,y) for y in np.linspace(.025,.975,9) for x in np.linspace(.025,.975,9)]).reshape(-1,1,2)
    expected=cv2.perspectiveTransform(coords,cv2.getPerspectiveTransform(unit,gt))
    pitch=np.mean([np.linalg.norm(gt[(i+1)%4]-gt[i]) for i in range(4)])/truth['n']
    errors=[]
    for rotation in range(4):
        actual=cv2.perspectiveTransform(coords,cv2.getPerspectiveTransform(unit,np.roll(q,rotation,axis=0)))
        errors.append(float(np.sqrt(np.mean(np.sum((actual-expected)**2,axis=2)))/pitch))
    return min(errors)

def load(path):return {r['id']:r for r in map(json.loads,path.read_text().splitlines())}

def evaluate(corpus,results,out):
    out.mkdir(parents=True,exist_ok=True)
    manifest=json.loads((corpus/'manifest.json').read_text());truth={r['id']:r for r in manifest}
    rows=[];correct={};summary=[];categories=[]
    base_recovery=load(results/'baseline-recovery.jsonl')
    existing_standard={cid for cid,t in truth.items() if t['positive'] and base_recovery[cid].get('standard_text')==t['payload']}
    existing_text={cid:(r.get('standard_text') if r.get('standard_text') is not None else r.get('selected_text')) for cid,r in base_recovery.items()}
    existing_auto={cid for cid,t in truth.items() if t['positive'] and existing_text[cid]==t['payload']}
    existing_wrong=sum(value is not None and (not truth[cid]['positive'] or value!=truth[cid]['payload']) for cid,value in existing_text.items())
    for engine in ENGINES:
        detections=load(results/f'{engine}.jsonl');recovery=load(results/f'{engine}-recovery.jsonl')
        assert set(detections)==set(truth)==set(recovery),f'Missing cases: {engine}'
        correct[engine]=set()
        for cid,t in truth.items():
            d=detections[cid];r=recovery[cid];pos=t['positive'];selected=r.get('selected_text')
            errors=[geometry_error(c['quad'],t) for c in d['candidates']] if pos else []
            loc=pos and any(e<=.5 for e in errors)
            def valid(c): return c.get('n')==t.get('n') and geometry_error(c['quad'],t)<=.5
            grid=pos and any(valid(c) for c in r['ranked'])
            top1=pos and bool(r['ranked']) and valid(r['ranked'][0])
            ok=pos and selected==t['payload']
            wrong=selected is not None and (not pos or selected!=t['payload'])
            if ok:correct[engine].add(cid)
            rows.append(dict(engine=engine,id=cid,category=t['category'],positive=pos,
                             proposed=bool(d['candidates']),localized=bool(loc),grid_top4=bool(grid),grid_top1=bool(top1),
                             correct=bool(ok),wrong=bool(wrong),detect_ms=d['ms'],recovery_ms=r['recovery_ms'],
                             budget_exhausted=r['budget_exhausted'],candidate_count=len(d['candidates']),
                             min_geometry_error_modules=min(errors) if errors else '',selected_text=selected or ''))
        er=[r for r in rows if r['engine']==engine];ps=[r for r in er if r['positive']];ns=[r for r in er if not r['positive']]
        timing=np.array([r['detect_ms'] for r in er]);rt=np.array([r['recovery_ms'] for r in er])
        summary.append(dict(engine=engine,positive_cases=len(ps),localized=sum(r['localized'] for r in ps),
                            grid_top4=sum(r['grid_top4'] for r in ps),grid_top1=sum(r['grid_top1'] for r in ps),
                            recovered=len(correct[engine]),wrong=sum(r['wrong'] for r in er),
                            negative_proposals=sum(r['proposed'] for r in ns),negative_cases=len(ns),
                            additional_vs_baseline=len(correct[engine]-correct['baseline']),
                            union_with_baseline=len(correct[engine]|correct['baseline']),
                            additional_vs_existing_auto=len(correct[engine]-existing_auto),
                            union_with_existing_auto=len(correct[engine]|existing_auto),
                            detect_p50_ms=float(np.median(timing)),detect_p95_ms=float(np.percentile(timing,95)),
                            recovery_p50_ms=float(np.median(rt)),recovery_p95_ms=float(np.percentile(rt,95)),
                            cases_budget_exhausted=sum(r['budget_exhausted']>0 for r in er)))
        for category in dict.fromkeys(t['category'] for t in manifest):
            group=[r for r in er if r['category']==category]
            categories.append(dict(engine=engine,category=category,cases=len(group),localized=sum(r['localized'] for r in group),
                                   grid_top4=sum(r['grid_top4'] for r in group),recovered=sum(r['correct'] for r in group),
                                   wrong=sum(r['wrong'] for r in group)))
    for name,items in [('cases',rows),('summary',summary),('categories',categories)]:
        with (out/f'{name}.csv').open('w') as f:
            w=csv.DictWriter(f,fieldnames=list(items[0]));w.writeheader();w.writerows(items)
    selected=existing_text.copy()
    cumulative=existing_auto.copy();steps=[dict(step='existing_standard_plus_periodicity',total=len(existing_auto),added=len(existing_auto))]
    for name,engines in [('add_zxing_java',['zxing_java']),('add_quirc_boofcv',['quirc','boofcv']),
                         ('add_zxing_cpp',['zxing_cpp']),('add_opencv_both',['opencv','opencv_aruco'])]:
        prev=len(cumulative)
        for e in engines:cumulative|=correct[e]
        for e in engines:
            for cid,r in load(results/f'{e}-recovery.jsonl').items():
                if selected[cid] is None: selected[cid]=r.get('selected_text')
        cascade_correct=sum(t['positive'] and selected[cid]==t['payload'] for cid,t in truth.items())
        cascade_wrong=sum(selected[cid] is not None and (not t['positive'] or selected[cid]!=t['payload']) for cid,t in truth.items())
        steps.append(dict(step=name,total=len(cumulative),added=len(cumulative)-prev,cascade_correct=cascade_correct,cascade_wrong=cascade_wrong))
    (out/'cumulative.json').write_text(json.dumps(steps,indent=2)+'\n')
    (out/'existing.json').write_text(json.dumps(dict(standard_correct=len(existing_standard),automatic_correct=len(existing_auto),automatic_wrong=existing_wrong),indent=2)+'\n')
    (out/'summary.json').write_text(json.dumps(summary,indent=2)+'\n')
    print(json.dumps(summary,indent=2));print('CUMULATIVE',steps)
    # Clean controls validate detector coordinates; downstream recovery failures remain measured.
    for engine in ENGINES:
        clean=[r for r in rows if r['engine']==engine and r['category']=='clean']
        assert len(clean)==12 and all(r['localized'] for r in clean),f'Clean control failed: {engine}'
    assert all(base_recovery[cid].get('standard_text')==t['payload'] for cid,t in truth.items() if t['category']=='clean'), 'Pristine corpus must be conventionally decodable'
    assert all(r['grid_top4'] for r in rows if r['engine']=='oracle' and r['positive']), 'Oracle coordinate control failed'

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('corpus',type=Path);p.add_argument('results',type=Path);p.add_argument('out',type=Path)
    a=p.parse_args();evaluate(a.corpus,a.results,a.out)
