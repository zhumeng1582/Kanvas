# Changelog

All notable changes to Kanvas are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/). The project is on the
`0.x` development line; see [versioning and migration](docs/migration.md) for
the compatibility policy.

## [Unreleased]

### Added

- Documented source and workspace-Maven consumption, build compatibility, and
  the current remote-publication status.
- Added a complete drawing persistence and symbol-restoration integration flow.
- Added English architecture and performance guides.
- Added migration, API-stability, device-test, and release-process guidance.

### Fixed

- Corrected the `KlineCandle` contract to identify `volume` as required.
- Updated StochRSI documentation to describe latest-candle patching and its
  full-calculation fallback.
- Clarified that Drawing owns magnifier configuration while Compose performs
  the Android `Modifier.magnifier` integration.
- Aligned the test target and Espresso with Android 16 / API 36 so Compose
  instrumentation can initialize without a compatibility permission screen.
- Refreshed the device-test pixel fingerprint after the current rendering-layer
  implementation was verified on the documented API 36 baseline.

[Unreleased]: https://github.com/zhumeng1582/Kanvas/commits/main
