#!/usr/bin/env python3
"""Generate a default-settings example requiring priority, single-segment forcing and list decoding.

Requires Pillow, qrcode, zxing-cpp and the repository's built desktop harness/readers.
No expected payload is supplied to recovery. Images and text matrices are synthetic.
"""
import argparse
import hashlib
import importlib.metadata
import json
import os
from pathlib import Path
import subprocess
import tempfile

from PIL import Image
import qrcode
import zxingcpp

PAYLOAD = 'QRevy repaired.'
VERSION = 1
MASK = 0
SCALE = 12
BORDER = 4
ERASED_ECC_WORDS = range(19, 23)
ERROR_WORDS = (2, 4)
PRIORITY_BIT = 1
CORE_SOURCE_ROOTS = ('shared/src/commonMain',
                     'androidApp/src/main/java/io/github/earthdiver/qrrepair/core',
                     'androidApp/src/main/java/com/google/zxing/qrcode/decoder')


def source_hash():
    repository = Path(__file__).resolve().parent.parent
    sources = sorted((path for root in CORE_SOURCE_ROOTS
                      for path in (repository / root).rglob('*') if path.suffix in {'.kt', '.java'}),
                     key=lambda path: path.relative_to(repository).as_posix())
    assert sources
    manifest = ''.join(f'{hashlib.sha256(path.read_bytes()).hexdigest()}  '
                       f'{path.relative_to(repository).as_posix()}\n' for path in sources)
    return hashlib.sha256(manifest.encode()).hexdigest()


def text_matrix(matrix):
    return [''.join('1' if cell else '0' for cell in row) for row in matrix]


def render(lines, before=None):
    n = len(lines)
    image = Image.new('RGB', (n + 2 * BORDER,) * 2, 'white')
    for y, row in enumerate(lines):
        for x, value in enumerate(row):
            color = {'0': (255, 255, 255), '1': (0, 0, 0),
                     '?': (128, 128, 128), '*': (128, 128, 128)}[value]
            if before is not None:
                if before[y][x] in '?*':
                    color = (37, 99, 235) if value == '1' else (219, 234, 254)
                elif before[y][x] != value:
                    color = (194, 65, 12) if value == '1' else (254, 215, 170)
            image.putpixel((x + BORDER, y + BORDER), color)
    return image.resize(((n + 2 * BORDER) * SCALE,) * 2, Image.Resampling.NEAREST)


def fixture():
    qr = qrcode.QRCode(version=VERSION, error_correction=qrcode.constants.ERROR_CORRECT_L,
                       border=0, mask_pattern=MASK)
    qr.add_data(qrcode.util.QRData(PAYLOAD, mode=qrcode.util.MODE_8BIT_BYTE))
    qr.make(fit=False)
    original = text_matrix(qr.get_matrix())
    n = len(original)
    function = qrcode.QRCode(version=VERSION, error_correction=qrcode.constants.ERROR_CORRECT_L, border=0)
    function.data_cache = []
    function.map_data = lambda *unused: None
    function.makeImpl(False, MASK)
    positions = []
    row, step = n - 1, -1
    for column in range(n - 1, 0, -2):
        if column <= 6:
            column -= 1
        while True:
            for x in (column, column - 1):
                if function.modules[row][x] is None:
                    positions.append((x, row))
            row += step
            if row < 0 or row >= n:
                row -= step
                step = -step
                break
    assert n == 21 and len(positions) == 26 * 8
    damaged = [list(line) for line in original]
    # V1-L has 19 data + 7 ECC words. The byte payload ends at bit 132;
    # the remaining 20 data bits are terminator, alignment and two pad bytes.
    suffix_start = 12 + len(PAYLOAD.encode('utf-8')) * 8
    assert suffix_start == 132
    for bit in range(suffix_start, 19 * 8):
        x, y = positions[bit]
        damaged[y][x] = '?'
    for word in ERASED_ECC_WORDS:
        for bit in range(word * 8, (word + 1) * 8):
            x, y = positions[bit]
            damaged[y][x] = '?'
    px, py = positions[PRIORITY_BIT]
    damaged[py][px] = '*'
    errors = [positions[word * 8 + 7] for word in ERROR_WORDS]
    for x, y in errors:
        damaged[y][x] = '0' if damaged[y][x] == '1' else '1'
    before = [''.join(row) for row in damaged]
    assert sum(row.count('?') + row.count('*') for row in before) == 53
    return qr, original, before, (px, py), errors


def run_harness(harness, command, source, output):
    subprocess.run([str(harness), command, str(source), str(output)], check=True,
                   env={**os.environ, 'JAVA_OPTS': '-Xmx2g'})
    return [json.loads(line) for line in output.read_text().splitlines()]


def completed(run, list_disabled=False):
    assert run['status'] == 'completed', run
    diagnostic = run['searchDiagnostic']
    assert diagnostic['coverageComplete'] and diagnostic['solverCompleteness'] == 'COMPLETE'
    assert not diagnostic['candidatesOmitted'] and not diagnostic['derivationsOmitted']
    if list_disabled:
        # A zero list budget deliberately suppresses both list stages. Preserve the
        # engine's resource-stop diagnostics; this is not a completed list search.
        assert diagnostic['incompleteReasons'] == ['LIST_DECODING_TIME_LIMIT']
        assert diagnostic['unsearched']
        assert diagnostic['listDecoding']['trials'] == 0
    else:
        assert not diagnostic['unsearched'] and not diagnostic['incompleteReasons']


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--harness', type=Path, required=True)
    parser.add_argument('--java-reader-classpath', required=True)
    parser.add_argument('--output', type=Path, default=Path('docs/examples/priority-list-decoding'))
    args = parser.parse_args()
    harness = args.harness.resolve()
    qr, original, before, priority, errors = fixture()
    # Only the single-segment assumption differs from the app's initial solver settings.
    settings = dict(limit='AUTOMATIC', partialBits=12, budgetMs=60000,
                    maxChecks=100000000, edits=[])
    with tempfile.TemporaryDirectory(prefix='qrevy-priority-list-') as temporary:
        work = Path(temporary)
        (work / 'before.txt').write_text('\n'.join(before) + '\n')
        (work / 'no-priority.txt').write_text('\n'.join(row.replace('*', '?') for row in before) + '\n')
        render(before).save(work / 'before.png')
        requests = []
        for action, single, lists, filename in [
                ('single-on', True, True, 'before.txt'),
                ('single-off', False, True, 'before.txt'),
                ('list-disabled', True, False, 'before.txt'),
                ('ordinary-unknowns', True, True, 'no-priority.txt')]:
            requests.append(dict(id='priority-list', kind='matrix', input=str(work / filename),
                                 action=action, single=single, listDecoding=lists, **settings))
        (work / 'requests.jsonl').write_text(''.join(json.dumps(r) + '\n' for r in requests))
        runs = run_harness(harness, 'run', work / 'requests.jsonl', work / 'runs.jsonl')
        assert len(runs) == 4
        for run in runs:
            action = run['request']['action']
            completed(run, list_disabled=action == 'list-disabled')
            assert len(run['candidates']) == (1 if action == 'single-on' else 0), action
        candidate = runs[0]['candidates'][0]
        assert candidate['text'] == PAYLOAD
        assert (candidate['version'], candidate['ecc'], candidate['mask']) == (1, 'L', 0)
        assert [word & 255 for word in candidate['codewords']] == qr.data_cache
        assert candidate['knownMismatches'] == 2 and candidate['constraintFilledBits'] == 20
        assert candidate['constraintForcedByUserSetting']
        assert candidate['singleSegmentStatus'] == 'APPLIED_WITH_PRIORITY_HEADER'
        assert candidate['priorityAssignments'] == [[*priority, original[priority[1]][priority[0]]]]
        route = candidate['actualBlockMethods'][0]['listDecoding']
        assert route['method'] == 'WU' and route['radius'] == 2
        assert route['remainingErasures'] == 4 and route['symbolErrors'] == 2
        assert not candidate['actualBlockMethods'][0]['bitConstraintSolved']
        # Obtain the image geometry through the repository's initialization code,
        # then sample the actual PNG with the default thresholds and one explicit *.
        (work / 'cases.json').write_text(json.dumps([
            dict(id='priority-list-image', kind='graphic', input=str(work / 'before.png'))]))
        prepared = work / 'prepared'
        subprocess.run([str(harness), 'prepare', str(work / 'cases.json'), str(prepared)], check=True)
        preparation = json.loads((prepared / 'inspection.jsonl').read_text())
        assert preparation['version'] == 1 and preparation['logicalAssigned']
        image_requests = []
        for action, single, lists, marks in [('single-on', True, True, [priority]),
                                             ('single-off', False, True, [priority]),
                                             ('list-disabled', True, False, [priority]),
                                             ('ordinary-unknowns', True, True, [])]:
            image_requests.append(dict(id='priority-list-image', kind='graphic', action=action,
                input=str(work / 'before.png'), single=single, listDecoding=lists, priority=marks,
                version=1, quad=preparation['quad'], logicalAssigned=True,
                automaticThresholds=True, blackMax=preparation['blackMax'], whiteMin=preparation['whiteMin'], fine=False,
                inverted=False, **settings))
        (work / 'image-requests.jsonl').write_text(''.join(json.dumps(r) + '\n' for r in image_requests))
        image_runs = run_harness(harness, 'run', work / 'image-requests.jsonl', work / 'image-runs.jsonl')
        for run in image_runs:
            completed(run, list_disabled=run['request']['action'] == 'list-disabled')
            assert len(run['candidates']) == (1 if run['request']['action'] == 'single-on' else 0)
        image_candidate = image_runs[0]['candidates'][0]
        assert image_candidate['codewords'] == candidate['codewords']
        sampled = (work / image_candidate['inputMatrix']).read_text().splitlines()
        assert sampled == [row.replace('*', '?') for row in before]
        assert image_candidate['constraintForcedByUserSetting']
        assert image_candidate['actualBlockMethods'][0]['listDecoding']['method'] == 'WU'
        restored = qrcode.QRCode(version=1, error_correction=qrcode.constants.ERROR_CORRECT_L,
                                border=0, mask_pattern=0)
        restored.data_cache = [word & 255 for word in candidate['codewords']]
        restored.makeImpl(False, 0)
        after = text_matrix(restored.modules)
        assert after == original
        readers = {}
        for label, lines in [('before', before), ('after', after)]:
            path = work / (label + '.png')
            render(lines).save(path)
            decoded = [b for b in zxingcpp.read_barcodes(render(lines), formats=zxingcpp.BarcodeFormat.QRCode) if b.valid]
            java = subprocess.run(['java', '-cp', args.java_reader_classpath, 'StandardReader'],
                                  input=json.dumps({'path': str(path)}) + '\n', text=True,
                                  capture_output=True, check=True)
            j = json.loads(java.stdout)
            assert 'error' not in j
            assert bool(decoded) == j['decoded'] == (label == 'after')
            if decoded:
                assert len(decoded) == 1 and decoded[0].text == PAYLOAD
            readers[label] = dict(zxing_cpp=bool(decoded), zxing_java=j['decoded'])
        args.output.mkdir(parents=True, exist_ok=True)
        for label, lines in [('before', before), ('after', after)]:
            (args.output / (label + '.txt')).write_text('\n'.join(lines) + '\n')
            render(lines).save(args.output / (label + '.png'))
        render(after, before=before).save(args.output / 'details.png')
        def comparison(run):
            return dict(action=run['request']['action'], candidate_count=len(run['candidates']),
                        search_diagnostic=run['searchDiagnostic'],
                        failure_reason=(run.get('failure') or {}).get('reason'))
        # Keep runtime paths and timings out of the reproducible artifact's requests.
        result = dict(input_type='Synthetic PNG and TEXT matrix', version=1, ecc='L', mask=0,
            payload=PAYLOAD, unknown_cells=53, total_cells=441, priority_cells=1,
            priority_coordinates_xy_zero_based=[priority], changed_cells_xy_zero_based=errors,
            erased_ecc_codewords_zero_based=list(ERASED_ECC_WORDS),
            erroneous_codewords_zero_based=list(ERROR_WORDS),
            erased_suffix_bit_range_half_open=[132, 152],
            settings=dict(single_segment_assumption=True, ecc='Automatic', mask='Automatic',
                format_error_limit='Automatic (6 bits)', data_ecc_error_limit='Automatic (2 bits)',
                priority_search_limit=4, partial_bit_search_limit=12, list_decoding_additional_errors=1,
                image_automatic_thresholds=True, image_black_max=preparation['blackMax'],
                image_white_min=preparation['whiteMin']),
            comparison=[comparison(r) for r in runs],
            list_disabled_control='ListDecodingBudget(0); ordinary stages complete, list stages intentionally skipped; time-limit diagnostics retained',
            selected_candidate=candidate,
            image_initialization=dict(version=preparation['version'], quad=preparation['quad'],
                source=preparation['source'], logical_assigned=preparation['logicalAssigned'],
                boundary_adapter=preparation['boundaryAdapter']),
            image_comparison=[comparison(r) for r in image_runs],
            image_candidate={k: v for k, v in image_candidate.items() if k != 'inputMatrix'},
            sampled_image_matches_text_matrix=True, original_matrix_match=True,
            reader_results=readers, core_source_sha256=source_hash(),
            generator_packages={p: importlib.metadata.version(p) for p in ['qrcode', 'Pillow', 'zxing-cpp']},
            sha256={p: hashlib.sha256((args.output / p).read_bytes()).hexdigest()
                    for p in ['before.txt', 'before.png', 'after.txt', 'after.png', 'details.png']})
        (args.output / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
        print('Verified PNG and TEXT recovery: one exact candidate; no candidates without single-segment forcing or priority.')
        print('Verified ordinary recovery with list stages disabled: no candidates.')


if __name__ == '__main__':
    main()
