# Notices

Dependency versions, artifact details, and third-party copyright and license texts are maintained in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). Include that document and the license files under `licenses/` with source distributions. The app displays a synchronized copy from `androidApp/src/main/res/raw/third_party_notices.txt`.

When dependencies change, update the canonical document and its app copy together, then reconcile the resolved runtime dependency graph using `scripts/release-audit.sh`. The inventory does not replace the release dependency review.

## QR Code trademark

“QR Code” is a registered trademark of DENSO WAVE INCORPORATED. This project is not affiliated with or endorsed by DENSO WAVE INCORPORATED.

## Reference material

The recovery approach was informed by Robert Xiao's write-up “MMA CTF 2015 - QR Code Recovery”. The project does not reproduce the article text or images and implements the recovery logic independently in Kotlin.
