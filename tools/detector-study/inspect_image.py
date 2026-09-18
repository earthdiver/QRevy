"""Opt-in external-image geometry diagnostic. Never writes image data into the repository."""
import argparse
import json
from pathlib import Path
import subprocess
import tempfile
import numpy as np
from PIL import Image
import zxingcpp


def inspect_image(path, jvm):
    with Image.open(path) as source:
        # Same integer BT.601 luminance formula as BitmapUtils.prepare.
        rgb = np.asarray(source.convert('RGB')).astype(np.int32)
        luma = ((299 * rgb[:, :, 0] + 587 * rgb[:, :, 1] + 114 * rgb[:, :, 2] + 500) // 1000).astype(np.uint8)
    results = zxingcpp.read_barcodes(luma, formats=zxingcpp.BarcodeFormat.QRCodeModel2,
                                   try_rotate=True, try_invert=False, try_downscale=True, return_errors=True)
    boundaries = []
    for result in results:
        p = result.position
        boundaries.append(dict(quad=[[v.x, v.y] for v in [p.top_left, p.top_right, p.bottom_right, p.bottom_left]]))
    # This study tool is Linux-only. Force temporary pixels outside the checkout, then delete.
    with tempfile.TemporaryDirectory(prefix='qr-image-inspection-', dir='/tmp') as work:
        image = Path(work) / 'input.png'
        Image.fromarray(luma).save(image)
        output = subprocess.check_output([str(jvm.resolve()), 'inspect-image', str(image), json.dumps(boundaries)], text=True)
    report = json.loads(output)
    report['native_model2_candidates'] = len(boundaries)
    report['scope'] = 'Desktop native detection and production shared geometry; not Android JNI or payload recovery'
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--image', type=Path, required=True, help='Explicit opt-in path; never embedded in source or saved reports')
    parser.add_argument('--jvm', type=Path, default=Path(__file__).resolve().parent / 'build/install/qr-detector-study/bin/qr-detector-study')
    args = parser.parse_args()
    inspect_image(args.image, args.jvm)
