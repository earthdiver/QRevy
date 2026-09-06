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

## Android validation

- [ ] `:androidApp:testDebugUnitTest`
- [ ] `:androidApp:lint`
- [ ] `:androidApp:assembleDebug`

## Privacy / licensing

- [ ] No new network permission, telemetry, cloud processing, or sensitive-data persistence
- [ ] Dependencies unchanged, or third-party license/notices were reviewed

## Notes for reviewer

<!-- Known limitations, manual test steps, screenshots, or follow-up work. -->
