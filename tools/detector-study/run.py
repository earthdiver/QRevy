"""Reproduce the study with a Python environment created from requirements.txt.
Downloads/builds upstream quirc into --work, never vendors third-party code into the repo.
"""
import argparse, hashlib, importlib.metadata, json, platform, shutil, subprocess, sys
from pathlib import Path
from corpus import make_corpus
from evaluate import evaluate, ENGINES

QUIRC_COMMIT='927d680904dc95fdff4cd9d022eb374b438ff8f2'

def run(cmd,**kwargs):
    print('+',' '.join(map(str,cmd)),flush=True)
    subprocess.run(list(map(str,cmd)),check=True,**kwargs)

def main(work):
    here=Path(__file__).resolve().parent;repo=here.parent.parent
    work=work.resolve();work.mkdir(parents=True,exist_ok=True)
    source=work/'quirc'
    if not source.exists():run(['git','clone','https://github.com/dlbeer/quirc.git',source])
    run(['git','-C',source,'checkout','--detach',QUIRC_COMMIT])
    assert not subprocess.check_output(['git','-C',str(source),'status','--porcelain']).strip(), 'quirc checkout must be clean'
    lib=work/'libquirc.so'
    run(['cc','-O2','-fPIC','-shared','-I',source/'lib',*[source/'lib'/f for f in ['quirc.c','identify.c','decode.c','version_db.c']],'-lm','-o',lib])
    run([repo/'bootstrap-wrapper.sh'],cwd=repo)
    run([repo/'scripts/verify-wrapper.sh'],cwd=repo)
    run([repo/'gradlew','-p',here,'installDist'],cwd=repo)
    corpus=work/'corpus';results=work/'results';results.mkdir(exist_ok=True)
    make_corpus(corpus)
    jvm=here/'build/install/qr-detector-study/bin/qr-detector-study'
    for engine in ENGINES[:-1]:
        if engine in ['baseline','zxing_java','boofcv']:
            cmd=[jvm,'detect',engine,corpus,results/f'{engine}.jsonl']
        else:
            cmd=[sys.executable,here/'native.py',engine,corpus,results/f'{engine}.jsonl','--quirc',lib]
        run(['/usr/bin/time','-f','%M','-o',results/f'{engine}-rss-kib.txt',*cmd])
    truth=json.loads((corpus/'manifest.json').read_text())
    with (results/'oracle.jsonl').open('w') as f:
        for t in truth:
            f.write(json.dumps(dict(id=t['id'],engine='oracle',ms=0,times=[],candidates=[dict(quad=t['quad'],n=t['n'],stage='ground_truth_control')] if t['positive'] else []))+'\n')
    for engine in ['oracle',*ENGINES[:-1]]:
        run([jvm,'recover',corpus,results/f'{engine}.jsonl',results/f'{engine}-recovery.jsonl'])
    evaluate(corpus,results,work/'report')
    fixed=work/'fixed-results';fixed.mkdir(exist_ok=True)
    for engine in ENGINES:
        shutil.copyfile(results/f'{engine}.jsonl',fixed/f'{engine}.jsonl')
        run([jvm,'recover-fixed',corpus,fixed/f'{engine}.jsonl',fixed/f'{engine}-recovery.jsonl'])
    evaluate(corpus,fixed,work/'fixed-report')
    write_metadata(here,work)
    print('REPORT',work/'report',flush=True)

def write_metadata(here,work):
    repo=here.parent.parent;lib=work/'libquirc.so'
    corpus=work/'corpus';results=work/'results'
    cpu=next((x.split(':',1)[1].strip() for x in Path('/proc/cpuinfo').read_text().splitlines() if x.startswith('model name')),'unknown')
    metadata=dict(source_commit=subprocess.check_output(['git','rev-parse','HEAD'],cwd=repo,text=True).strip(),
                  os=platform.system(),machine=platform.machine(),cpu=cpu,python=platform.python_version(),
                  versions={x:importlib.metadata.version(x) for x in ['numpy','opencv-python-headless','Pillow','qrcode','zxing-cpp']},
                  quirc_commit=QUIRC_COMMIT,quirc_library_bytes=lib.stat().st_size,
                  manifest_sha256=hashlib.sha256((corpus/'manifest.json').read_bytes()).hexdigest(),
                  max_rss_kib={e:int((results/f'{e}-rss-kib.txt').read_text().strip()) for e in ENGINES[:-1]},
                  harness_sha256={str(p.relative_to(here)):hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(here.rglob('*')) if p.is_file() and ('build' not in p.relative_to(here).parts) and ('.gradle' not in p.relative_to(here).parts) and ('.kotlin' not in p.relative_to(here).parts) and p.suffix in ['.py','.kt','.kts']},
                  jvm_jars_bytes=sum(p.stat().st_size for p in (here/'build/install/qr-detector-study/lib').glob('*.jar')))
    (work/'report/environment.json').write_text(json.dumps(metadata,indent=2)+'\n')

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--work',type=Path,required=True)
    main(p.parse_args().work)
