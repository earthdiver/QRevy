#!/usr/bin/env python3
"""Generate the image-first hole example and verify default-setting recovery.

Requires Pillow, qrcode, zxing-cpp and the built scripts/example-harness tool.
The recovery input is the damaged PNG plus visible-color soft edits, never a payload.
"""
import argparse
import hashlib
import importlib.metadata
import json
import math
import os
from pathlib import Path
import subprocess
import tempfile

from PIL import Image, ImageDraw
import qrcode
import zxingcpp

REPOSITORY = Path(__file__).resolve().parent.parent
PAYLOAD = 'QRevy app: recovered QR.'
VERSION, MASK, SCALE, BORDER = 3, 3, 24, 4
GRAY = (128, 128, 128)
PRIORITY = (28, 28)
SCORCH = (18, 11)
HOLES = [
    (1.6370006111006983, 2.3157925511381676, 6.149000000000001),
    (1.9943490108559894, 25.8404790623738, 6.149000000000001),
    (10.68664657498296, 1.1630683147060088, 6.149000000000001),
    (10.861888370391702, 12.262354342587797, 6.149000000000001),
    (12.976323891975104, 23.802268427537022, 7.267),
    (21.923576672470745, 13.659819227378733, 3.9130000000000003),
    (15.691467774570395, 6.80873117152976, 3.9130000000000003),
    (17.05440453487911, 16.24633984340928, 3.9130000000000003),
    (28, 28, 0.85), (17.5, 3.5, 2.2),
]
CORE_SOURCE_ROOTS = ('shared/src/commonMain',
                     'androidApp/src/main/java/io/github/earthdiver/qrrepair/core',
                     'androidApp/src/main/java/com/google/zxing/qrcode/decoder')


def core_source_sha256():
    sources = sorted((p for root in CORE_SOURCE_ROOTS for p in (REPOSITORY / root).rglob('*')
                      if p.suffix in {'.kt', '.java'}), key=lambda p: p.relative_to(REPOSITORY).as_posix())
    manifest = ''.join(f'{hashlib.sha256(p.read_bytes()).hexdigest()}  '
                       f'{p.relative_to(REPOSITORY).as_posix()}\n' for p in sources)
    return hashlib.sha256(manifest.encode()).hexdigest()


def render(matrix):
    image = Image.new('RGB', ((len(matrix) + 2 * BORDER) * SCALE,) * 2, 'white')
    draw = ImageDraw.Draw(image)
    for y, row in enumerate(matrix):
        for x, value in enumerate(row):
            draw.rectangle(((x + BORDER) * SCALE, (y + BORDER) * SCALE,
                            (x + BORDER + 1) * SCALE - 1, (y + BORDER + 1) * SCALE - 1),
                           fill='black' if value else 'white')
    return image


def damaged_image(original):
    image = render(original)
    draw = ImageDraw.Draw(image)
    for i, (cx, cy, radius) in enumerate(HOLES):
        points = []
        for step in range(360):
            angle = step * math.tau / 360
            r = radius * (1 + .045 * math.sin(5 * angle + i) + .03 * math.cos(9 * angle - 2 * i))
            points.append(((cx + .5 + BORDER + r * math.cos(angle)) * SCALE,
                           (cy + .5 + BORDER + r * math.sin(angle)) * SCALE))
        draw.polygon(points, fill=GRAY)
    x, y = SCORCH
    draw.rectangle(((x + BORDER) * SCALE, (y + BORDER) * SCALE,
                    (x + BORDER + 1) * SCALE - 1, (y + BORDER + 1) * SCALE - 1), fill='black')
    return image


def image_evidence(image, dimension):
    rows = []
    for y in range(dimension):
        row = ''
        for x in range(dimension):
            points = [image.getpixel((int((x + .5 + BORDER + dx) * SCALE),
                                      int((y + .5 + BORDER + dy) * SCALE)))
                      for dy in (-1 / 3, 0, 1 / 3) for dx in (-1 / 3, 0, 1 / 3)]
            if all(p == GRAY for p in points):
                value = '?'
            else:
                crop = image.crop(((x + BORDER) * SCALE, (y + BORDER) * SCALE,
                                   (x + BORDER + 1) * SCALE, (y + BORDER + 1) * SCALE))
                counts = {color: count for count, color in crop.getcolors()}
                black, white = counts.get((0, 0, 0), 0), counts.get((255, 255, 255), 0)
                assert black != white, (x, y, 'ambiguous surviving color')
                value = '1' if black > white else '0'
            if (x, y) == PRIORITY:
                assert value == '?'
                value = '*'
            row += value
        rows.append(row)
    return rows


def invoke(harness, command, source, output, request):
    source.write_text(json.dumps(request) + '\n')
    subprocess.run([str(harness), command, str(source), str(output)], check=True,
                   env={**os.environ, 'JAVA_OPTS': '-Xmx2g'})
    return json.loads(output.read_text())


def completed(run):
    assert run['status'] == 'completed', run
    d = run['searchDiagnostic']
    assert d['coverageComplete'] and d['solverCompleteness'] == 'COMPLETE'
    assert not d['unsearched'] and not d['incompleteReasons']
    assert not d['candidatesOmitted'] and not d['derivationsOmitted']


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--harness', type=Path, default=REPOSITORY /
                        'scripts/example-harness/build/install/qr-example-verifier/bin/qr-example-verifier')
    parser.add_argument('--output', type=Path, default=REPOSITORY / 'docs/examples')
    args = parser.parse_args()
    qr = qrcode.QRCode(version=VERSION, error_correction=qrcode.constants.ERROR_CORRECT_M,
                       border=0, mask_pattern=MASK)
    qr.add_data(qrcode.util.QRData(PAYLOAD, mode=qrcode.util.MODE_8BIT_BYTE))
    qr.make(fit=False)
    original = qr.get_matrix()
    n = len(original)
    image = damaged_image(original)
    output = args.output / 'bullet-holes'
    output.mkdir(parents=True, exist_ok=True)
    image.save(output / 'before.png')
    # Read the saved raster before deriving erasures or edits; no damaged TEXT matrix is rendered.
    image = Image.open(output / 'before.png').convert('RGB')
    rows = image_evidence(image, n)
    assert sum(c in '?*' for row in rows for c in row) == 503
    (output / 'before.txt').write_text('\n'.join(rows) + '\n')
    quad = [[108, 108], [780, 108], [780, 780], [108, 780]]
    with tempfile.TemporaryDirectory(prefix='qrevy-hole-example-') as temporary:
        work = Path(temporary)
        sampled = invoke(args.harness, 'sample', work / 'sample.json', work / 'sampled.json',
                         dict(input=str((output / 'before.png').resolve()), version=VERSION, quad=quad))
        assert (sampled['blackMax'], sampled['whiteMin']) == (4, 229)
        edits = []
        for y in range(n):
            for x in range(n):
                expected, observed = rows[y][x], sampled['rows'][y][x]
                if expected in '?*':
                    assert observed == '?', (x, y, expected, observed)
                elif observed == '?':
                    edits.append([x, y, expected, True])
                else:
                    assert observed == expected, (x, y, expected, observed)
        assert len(edits) == 62
        (output / 'soft-edits.json').write_text(json.dumps(edits, indent=2) + '\n')
        base = dict(id='bullet-holes', kind='image', input=str((output / 'before.png').resolve()),
                    version=VERSION, quad=quad, logicalAssigned=True, automaticThresholds=True,
                    blackMax=sampled['provisionalBlackMax'], whiteMin=sampled['provisionalWhiteMin'],
                    limit='AUTOMATIC', partialBits=12, fine=False,
                    budgetMs=60000, maxChecks=100000000, edits=edits, priority=[list(PRIORITY)])
        requests = [dict(base, action='single-on', single=True),
                    dict(base, action='single-off', single=False),
                    dict(base, action='no-priority', single=True, priority=[])]
        (work / 'requests.jsonl').write_text(''.join(json.dumps(r) + '\n' for r in requests))
        subprocess.run([str(args.harness), 'run', str(work / 'requests.jsonl'), str(work / 'runs.jsonl')],
                       check=True, env={**os.environ, 'JAVA_OPTS': '-Xmx2g'})
        runs = [json.loads(line) for line in (work / 'runs.jsonl').read_text().splitlines()]
        for run in runs:
            completed(run)
        assert [len(r['candidates']) for r in runs] == [1, 0, 0]
        candidate = runs[0]['candidates'][0]
        assert candidate['text'] == PAYLOAD and [v & 255 for v in candidate['codewords']] == qr.data_cache
        assert candidate['singleSegmentStatus'] == 'APPLIED_WITH_PRIORITY_HEADER'
        assert candidate['constraintForcedByUserSetting']
        assert candidate['knownMismatches'] == 1
        assert candidate['actualBlockMethods'][0]['bitConstraintSolved']
        assert candidate['actualBlockMethods'][0]['listDecoding'] is None
        assert [a[:2] for a in candidate['priorityAssignments']] == [list(PRIORITY)]
        sampled_rows = (work / candidate.pop('inputMatrix')).read_text().splitlines()
        assert sampled_rows == [row.replace('*', '?') for row in rows]
        conventional = invoke(args.harness, 'conventional', work / 'rs.json', work / 'rs-results.json',
                              dict(input=str((output / 'before.txt').resolve()), ecc='M', mask=MASK))
        for r in conventional:
            for b in r['blocks']:
                assert not b['ordinaryRsSucceeded'] and b['assignedOrdinaryRsCandidates'] == 0
                assert b['minimumBitsToReachRsErasureCapacity'] > 12
        forced = next(r for r in conventional if r['priorityAssignment'] == 0 and r['single'])
        assert forced['blocks'][0]['erasures'] == 35
        assert forced['blocks'][0]['minimumBitsToReachRsErasureCapacity'] == 14
        repaired = qrcode.QRCode(version=VERSION, error_correction=qrcode.constants.ERROR_CORRECT_M,
                                border=0, mask_pattern=MASK)
        repaired.data_cache = [v & 255 for v in candidate['codewords']]
        repaired.makeImpl(False, MASK)
        assert repaired.modules == original
        after = [''.join('1' if v else '0' for v in row) for row in repaired.modules]
        (output / 'after.txt').write_text('\n'.join(after) + '\n')
        render(repaired.modules).save(output / 'after.png')
        details = render(repaired.modules)
        draw = ImageDraw.Draw(details)
        for y in range(n):
            for x in range(n):
                if rows[y][x] in '?*':
                    color = (37, 99, 235) if original[y][x] else (219, 234, 254)
                elif rows[y][x] != after[y][x]:
                    color = (251, 146, 60)
                else:
                    continue
                draw.rectangle(((x + BORDER) * SCALE, (y + BORDER) * SCALE,
                                (x + BORDER + 1) * SCALE - 1, (y + BORDER + 1) * SCALE - 1), fill=color)
        details.save(output / 'details.png')
        reader = {name: [b.text for b in zxingcpp.read_barcodes(Image.open(output / (name + '.png')),
                  formats=zxingcpp.BarcodeFormat.QRCode) if b.valid] for name in ('before', 'after')}
        assert reader == dict(before=[], after=[PAYLOAD])
        result = dict(input_type='PNG image with visible-color soft edits', version=VERSION, ecc='M', mask=MASK,
                      payload=PAYLOAD, scale=SCALE, border=BORDER, holes=HOLES, black_scorch_cells=[list(SCORCH)],
                      geometry_rule='r(theta)=r*(1+0.045*sin(5*theta+i)+0.03*cos(9*theta-2*i))',
                      erasure_rule='All nine 3x3 subcell center pixels are gray; other unknown cells use visible-color soft edits.',
                      unknown_cells=502, priority_cells=1, erased_cells_total=503, total_cells=n*n,
                      soft_edits=edits, settings=dict(single_segment=True, ecc='Automatic', mask='Automatic',
                      automatic_thresholds=True, black_max=sampled['blackMax'], white_min=sampled['whiteMin'],
                      provisional_black_max=sampled['provisionalBlackMax'],
                      provisional_white_min=sampled['provisionalWhiteMin'], data_ecc_error_limit=5,
                      format_error_limit=6, priority_limit=4, partial_bit_limit=12, list_additional_errors=1),
                      module_center_quad=quad, comparison=[dict(action=r['request']['action'],
                      candidate_count=len(r['candidates']), search_diagnostic=r['searchDiagnostic']) for r in runs],
                      selected_candidate=candidate, conventional_controls=conventional, reader_results=reader,
                      original_codewords_match=True, original_matrix_match=True,
                      core_source_sha256=core_source_sha256(), generator_packages={p:importlib.metadata.version(p)
                      for p in ('qrcode','Pillow','zxing-cpp')}, sha256={name:hashlib.sha256((output/name).read_bytes()).hexdigest()
                      for name in ('before.png','before.txt','after.png','after.txt','details.png','soft-edits.json')})
        (output / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
    print('Verified image-first hole example: 503/841 erased, 62 soft edits, one priority bit; ON=1, OFF=0, no priority=0.')


if __name__ == '__main__':
    main()
