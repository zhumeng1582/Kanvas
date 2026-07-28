# Repository Guidelines

## Project Structure & Module Organization

Kanvas is a Kotlin/Android Compose multi-module project:

- `kanvas-core/` contains platform-independent chart models, viewport math, controllers, and unit tests.
- `kanvas-compose/` contains the Compose chart renderer, indicators, interaction handling, and UI tests.
- `kanvas-drawing/` contains drawing tools and their geometry/controller tests.
- `example/` is the runnable reference application and visual integration target.
- `docs/`, `README*.md`, and `ARCHITECTURE.zh-CN.md` document usage and design decisions.

Keep production code under each module's `src/main`; place JVM tests under `src/test` and device tests under `src/androidTest`.

## Build, Test, and Development Commands

Run commands from the repository root:

```bash
./gradlew test                         # all JVM unit tests
./gradlew :kanvas-compose:test         # Compose module tests
./gradlew :example:assembleDebug       # build the example APK
./gradlew lintRelease                  # Android lint checks
./gradlew test lintRelease assembleRelease
```

Use `./gradlew :kanvas-compose:connectedDebugAndroidTest` when an Android emulator/device is available. Do not commit generated `build/` outputs.

## Coding Style & Naming Conventions

Use Kotlin/Compose conventions: four-space indentation, trailing commas in multiline declarations, `PascalCase` for types/composables, and `camelCase` for functions, properties, and test names. Keep packages under `com.zhumeng.kanvas`. Prefer small pure functions for viewport and interaction math, with comments only where behavior is non-obvious.

## Testing Guidelines

Tests use Kotlin Test/JUnit through Gradle. Name tests after behavior, for example ``blank cross snaps to the nearest candle``. Add pure math tests in `kanvas-core`; renderer/layout behavior belongs in `kanvas-compose`; device-specific behavior belongs in `src/androidTest`. Run the narrowest relevant module test first, then the full `test` task before submitting.

## Commit & Pull Request Guidelines

Use concise imperative commit subjects with the project’s existing style, such as `feat: ...`, `fix: ...`, or `docs: ...`. Keep commits focused. Pull requests should explain the user-visible change, identify affected modules, list verification commands, and include screenshots or recordings for visual chart changes. Mention any API, package, or migration impact explicitly.

## Configuration and Security

Keep signing credentials, Maven credentials, and `local.properties` local. Never commit secrets, emulator captures, generated artifacts, or private tokens. Review third-party licensing obligations before redistributing the project.
