#!/usr/bin/env python3
"""Generate irregular bullet-like holes and verify conditional priority recovery.

Requires Pillow, qrcode, zxing-cpp, and separately supplied local evaluation tools.
The input is a TEXT matrix; the PNG is its visualization, not an image-import test.
"""
import argparse
import hashlib
import importlib.metadata
import json
import math
from PIL import Image, ImageDraw
import os
from pathlib import Path
import subprocess
import tempfile

import qrcode
import zxingcpp

SCALE = 12
BORDER = 4


def render(lines, filled=None):
    n = len(lines)
    image = Image.new('RGB', (n + 2 * BORDER,) * 2, 'white')
    for y, line in enumerate(lines):
        for x, value in enumerate(line):
            color = {'0': (255, 255, 255), '1': (0, 0, 0), '?': (128, 128, 128)}[value]
            if filled is not None and filled[y][x] == '?':
                color = (37, 99, 235) if value == '1' else (219, 234, 254)
            image.putpixel((x + BORDER, y + BORDER), color)
    return image.resize(((n + 2 * BORDER) * SCALE,) * 2, Image.Resampling.NEAREST)


def text_matrix(matrix):
    return [''.join('1' if cell else '0' for cell in row) for row in matrix]


PAYLOAD = 'QR Repair: recovered QR.'
HOLES = [(2, 2, 5.5), (2, 26, 5.5), (11, 1, 5.5), (11, 12, 5.5),
         (13, 24, 6.5), (22, 14, 3.5), (16, 7, 3.5), (17, 16, 3.5), (28, 28, 2.05)]


def edge_radius(angle, hole_index, radius):
    return radius * (1 + .08 * math.sin(5 * angle + hole_index)
                     + .05 * math.cos(9 * angle - 2 * hole_index))


def missing(x, y):
    for i, (cx, cy, radius) in enumerate(HOLES):
        angle = math.atan2(y - cy, x - cx)
        if (x - cx) ** 2 + (y - cy) ** 2 <= edge_radius(angle, i, radius) ** 2:
            return True
    return False


def damaged_image(original):
    image = render(original)
    draw = ImageDraw.Draw(image)
    for i, (cx, cy, radius) in enumerate(HOLES):
        points = []
        for step in range(360):
            angle = step * math.tau / 360
            r = edge_radius(angle, i, radius)
            points.append(((cx + r * math.cos(angle) + 4.5) * 12,
                           (cy + r * math.sin(angle) + 4.5) * 12))
        draw.polygon(points, fill=(128, 128, 128))
    return image


def preview(lines):
    return render([row.replace('*', '?') for row in lines])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--harness', type=Path, required=True)
    parser.add_argument('--java-reader-classpath', required=True)
    parser.add_argument('--output', type=Path, default=Path('docs/examples'))
    args = parser.parse_args()
    qr = qrcode.QRCode(version=3, error_correction=qrcode.constants.ERROR_CORRECT_M,
                       border=0, mask_pattern=3)
    qr.add_data(qrcode.util.QRData(PAYLOAD, mode=qrcode.util.MODE_8BIT_BYTE))
    qr.make(fit=False)
    original = text_matrix(qr.get_matrix())
    n = len(original)
    assert n == 29
    basic = [''.join('?' if missing(x, y) else original[y][x] for x in range(n)) for y in range(n)]
    priority = [list(row) for row in basic]
    header_bits = []
    # Version 3-M has one block. Its first 12 stream bits follow the lower-right zigzag.
    assert len(qrcode.base.rs_blocks(3, qrcode.constants.ERROR_CORRECT_M)) == 1
    for bit in range(12):
        y, x = n - 1 - bit // 2, n - 1 - bit % 2
        if priority[y][x] == '?':
            priority[y][x] = '*'
            header_bits.append(bit)
    priority = [''.join(row) for row in priority]
    assert header_bits == [0, 1, 2, 3, 4]
    function = qrcode.QRCode(version=3, error_correction=qrcode.constants.ERROR_CORRECT_M, border=0)
    function.data_cache = []
    function.map_data = lambda *unused: None
    function.makeImpl(False, 3)
    function_total = sum(cell is not None for row in function.modules for cell in row)
    function_missing = sum(function.modules[y][x] is not None and basic[y][x] == '?'
                           for y in range(n) for x in range(n))
    positions = []
    row, step = n - 1, -1
    for column in range(n - 1, 0, -2):
        if column <= 6:
            column -= 1
        while True:
            for x in (column, column - 1):
                if function.modules[row][x] is None:
                    positions.append((row, x))
            row += step
            if row < 0 or row >= n:
                row -= step
                step = -step
                break
    data_positions = positions[:8 * len(qr.data_cache)]
    remainder_positions = positions[8 * len(qr.data_cache):]
    data_ecc_missing = sum(basic[y][x] == '?' for y, x in data_positions)
    remainder_missing = sum(basic[y][x] == '?' for y, x in remainder_positions)
    assert data_ecc_missing + remainder_missing + function_missing == sum(row.count('?') for row in basic)
    settings = dict(kind='matrix', limit='EXACT', fine=False, partialBits=12,
                    budgetMs=60000, maxChecks=100000000, edits=[])
    with tempfile.TemporaryDirectory(prefix='qr-priority-examples-') as temporary:
        work = Path(temporary)
        requests = []
        variants = [('bullet-holes', priority)]
        for name, lines in variants:
            (work / (name + '.txt')).write_text('\n'.join(lines) + '\n')
            for single in [False, True]:
                requests.append(dict(id=name, input=str(work / (name + '.txt')),
                                     action='single-on' if single else 'single-off', single=single, **settings))
        (work / 'no-priority.txt').write_text('\n'.join(row.replace('*', '?') for row in priority) + '\n')
        requests.append(dict(id='bullet-holes', input=str(work / 'no-priority.txt'),
                             action='ordinary-unknowns', single=True, **settings))
        (work / 'requests.jsonl').write_text(''.join(json.dumps(r) + '\n' for r in requests))
        subprocess.run([str(args.harness.resolve()), 'run', str(work / 'requests.jsonl'), str(work / 'runs.jsonl')],
                       check=True, env={**os.environ, 'JAVA_OPTS': '-Xmx2g'})
        runs = [json.loads(line) for line in (work / 'runs.jsonl').read_text().splitlines()]
        for name, lines in variants:
            group = [r for r in runs if r['id'] == name]
            selected = next(r for r in group if r['request']['action'] == 'single-on')
            for r in group:
                assert r['status'] == 'completed'
                if r is not selected:
                    assert not r['candidates'], (name, r['request']['action'])
            assert len(selected['candidates']) == 1
            c = selected['candidates'][0]
            d = selected['searchDiagnostic']
            assert c['text'] == PAYLOAD and c['knownMismatches'] == 0
            assert d['solverCompleteness'] == 'COMPLETE' and d['coverageComplete']
            assert not d['unsearched'] and not d['incompleteReasons'] and not d['candidatesOmitted']
            assert c['constraintForcedByUserSetting']
            assert c['singleSegmentStatus'] == 'APPLIED_WITH_PRIORITY_HEADER'
            assert any(b['bitConstraintSolved'] for b in c['actualBlockMethods'])
            assignments = c['priorityAssignments']
            assert len(assignments) == 5
            for x, y, value in assignments:
                assert lines[y][x] == '*' and original[y][x] == value
            repaired = qrcode.QRCode(version=c['version'], error_correction=qrcode.constants.ERROR_CORRECT_M,
                                     border=0, mask_pattern=c['mask'])
            repaired.data_cache = [value & 255 for value in c['codewords']]
            repaired.makeImpl(False, c['mask'])
            after = text_matrix(repaired.modules)
            assert after == original
            output = args.output / name
            output.mkdir(parents=True, exist_ok=True)
            for label, matrix in [('before', lines), ('after', after)]:
                (output / (label + '.txt')).write_text('\n'.join(matrix) + '\n')
                preview(matrix).save(output / (label + '.png'))
            preview(lines).save(output / 'input-matrix.png')
            damaged_image(original).save(output / 'before.png')
            filled = [row.replace('*', '?') for row in lines]
            render(after, filled=filled).save(output / 'details.png')
            readers = {}
            # Check the contour illustration and module-level visualizations.
            for label, matrix in [('original', original), ('before', lines), ('after', after),
                                  ('before-gray', filled), ('input-matrix', lines)]:
                image = damaged_image(original) if label == 'before' else preview(matrix)
                path = work / (label + '.png')
                image.save(path)
                decoded = [b for b in zxingcpp.read_barcodes(image, formats=zxingcpp.BarcodeFormat.QRCode) if b.valid]
                java = subprocess.run(['java', '-cp', args.java_reader_classpath, 'StandardReader'],
                                      input=json.dumps({'path': str(path)}) + '\n', text=True,
                                      capture_output=True, check=True)
                j = json.loads(java.stdout)
                assert 'error' not in j
                assert bool(decoded) == j['decoded'] == (label in ['original', 'after'])
                if decoded:
                    assert len(decoded) == 1 and decoded[0].text == PAYLOAD
                readers[label] = dict(zxing_cpp=bool(decoded), zxing_java=j['decoded'])
            comparison = []
            for r in group:
                comparison.append(dict(action=r['request']['action'], single=r['request']['single'],
                                       candidate_count=len(r['candidates']), search_diagnostic=r['searchDiagnostic'],
                                       failure_reason=(r.get('failure') or {}).get('reason')))
            result = dict(input_type='TEXT matrix (PNG is visualization)', version=3, ecc='M', mask=3,
                          payload=PAYLOAD, holes=HOLES, priority_header_bits=header_bits,
                          geometry_rule='r(theta) = r * (1 + 0.08*sin(5*theta+i) + 0.05*cos(9*theta-2*i)); missing at module center',
                          function_missing=function_missing, function_total=function_total,
                          data_ecc_missing=data_ecc_missing, remainder_missing=remainder_missing,
                          data_ecc_total=8*len(qr.data_cache), remainder_total=n*n-function_total-8*len(qr.data_cache),
                          unknown_cells=sum(row.count('?') for row in lines), priority_cells=len(assignments),
                          total_cells=n*n, settings=settings, comparison=comparison, selected_candidate=c,
                          reader_results=readers, original_matrix_match=True,
                          core_commit=subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip(),
                          generator_packages={p: importlib.metadata.version(p) for p in ['qrcode', 'Pillow', 'zxing-cpp']},
                          sha256={p: hashlib.sha256((output / p).read_bytes()).hexdigest()
                                  for p in ['before.txt', 'before.png', 'input-matrix.png', 'after.txt', 'after.png', 'details.png']})
            (output / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
            print(f"Verified {name}: {result['unknown_cells']} '?' + {len(assignments)} '*'; OFF=0, ON=1 exact candidate.")


if __name__ == '__main__':
    main()
