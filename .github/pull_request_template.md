## Summary

<!-- What changed and why? -->

## Recovery / correctness impact

- [ ] No recovery-math behavior changed
- [ ] Recovery behavior changed and regression tests were added/updated
- [ ] Provenance / user FIXED constraints remain preserved

## Platform boundary

- [ ] `./scripts/check-shared-boundary.sh` passes
- [ ] No Android/Java/ZXing APIs were added to `shared/commonMain`
- [ ] If shared code changed, `:shared:jvmTest` passes

## Validation

- [ ] Selected checks passed with `python3 -B scripts/check-changes.py` (or `--full`)
- Change base and selected scope:
- Additional checks / release validation:

<!-- Use --base <ref> for committed changes. A clean tree without --base selects no tests.
     Full checks are required for recovery, build/check configuration, or unclassified changes.
     Record any direct Gradle test filters and explain checks that are not applicable. -->

## Privacy / licensing

- [ ] No new network permission, telemetry, cloud processing, or sensitive-data persistence
- [ ] Dependencies unchanged, or third-party license/notices were reviewed

## Notes for reviewer

<!-- Known limitations, manual test steps, screenshots, or follow-up work. -->
