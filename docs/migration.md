# Versioning, API stability, and migration

## Current status

Kanvas `0.1.0` is a development version. This repository currently documents
source-project and workspace-Maven consumption; a generally available remote
Maven coordinate is not part of the supported installation contract until a
release announcement adds it.

The build uses Kotlin's binary compatibility validator and stores public API
baselines under each library module's `api/` directory. `./gradlew apiCheck`
detects accidental signature changes, but a passing binary check does not make
behavior, rendering, persistence formats, or experimental integration points
stable by itself.

## Compatibility policy

- While the project remains below `1.0.0`, public APIs may change between minor
  versions. Intentional breaking changes must be called out in `CHANGELOG.md`
  and in a version-specific section of this guide.
- Patch releases should remain source and binary compatible unless they fix a
  contract that could not be used safely as documented. Any exception must be
  explicit in the release notes.
- APIs marked with an experimental annotation have no compatibility guarantee
  until the annotation is removed. Unmarked APIs are still governed by the
  `0.x` policy above.
- `KlineCandle` ordering, millisecond timestamps, newest-first indicator-column
  alignment, and timestamp/value drawing coordinates are data contracts. A
  change to one requires a migration path even when Kotlin signatures remain
  compatible.
- Serialized drawing overlays are owned by the host. Hosts should version their
  stored representation and convert it to the current `DrawingOverlay` model at
  the repository boundary.

## Upgrading a source integration

1. Read `CHANGELOG.md` from the previously used revision through the target
   revision.
2. Review `git diff` for each consumed module's `api/*.api` file.
3. Rebuild feed adapters and verify newest-first ordering, page overlap removal,
   timestamp units, and required OHLCV fields.
4. Restore persisted drawings for at least two symbols and verify a symbol
   switch saves the previous session before restoring the next one.
5. Verify custom indicator and main-chart renderer cache policies. Use
   `ImmutableInputs` only for output fully determined by immutable inputs.
6. Run JVM tests, the example release build, lint, and device tests on the
   application's supported device matrix.

## Version-specific migrations

There is no migration from an earlier supported remote Kanvas release to
`0.1.0`. Future breaking releases will add concrete before/after code and data
migration instructions here before publication.
