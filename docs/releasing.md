# Release checklist

This document turns the Gradle publication support into an operational release
process. Release credentials must stay outside the repository.

## 1. Prepare the release

- Choose the version and update the root `allprojects.version` value.
- Move relevant `CHANGELOG.md` entries from `Unreleased` into a dated version
  section and add a new empty `Unreleased` section.
- Update the README build/compatibility table and remote installation snippet.
  If Central publication is not complete, keep the source/workspace-only notice.
- Add migration steps for every intentional source, binary, behavioral, or
  persisted-data incompatibility.
- Run `./gradlew apiCheck`. For an intentional public API change, review the
  diff before running `./gradlew apiDump`, and commit the changed `api/*.api`
  baselines with the implementation.

## 2. Verify source and devices

Run the deterministic JVM and Android build gates:

```bash
./gradlew apiCheck test lintRelease assembleRelease :example:assembleDebug
```

Run Compose instrumentation on a clean, dedicated AVD or device with no setup
or permission dialog covering the test Activity:

```bash
./gradlew :kanvas-compose:connectedDebugAndroidTest
```

Record Android release, API level, ABI, device/AVD name, and the number of tests
in the release notes. The current local baseline is Android 16 / API 36 ARM64.
Library `minSdk` is not a substitute for a tested device matrix.

Run the strict performance workflow on its configured physical-device matrix
when rendering or interaction hot paths changed. Do not describe a release as
stable 120 fps unless every documented strict gate passes.

## 3. Verify publications locally

```bash
./gradlew publishAllPublicationsToWorkspaceRepository verifyPublishedPomScopes
```

Inspect `build/maven-repository/com/zhumeng/` and verify, for every library
module:

- release AAR or JAR;
- Gradle module metadata and POM;
- sources and Dokka Javadoc JARs;
- compile scopes for dependencies exposed by public signatures;
- the expected group, artifact, version, license, SCM, and developer metadata.

Create a small consumer project against only that workspace repository and
compile a `KanvasChart` integration before uploading.

Publication is incomplete until every Dokka Javadoc task finishes. If a Dokka
task stops producing output, rerun that module's
`dokkaGeneratePublicationJavadoc` with `--stacktrace --info` in CI or another
unrestricted environment. Do not treat generated POMs alone as a successful
publication.

## 4. Sign and upload

Provide credentials through environment variables or their Gradle-property
equivalents:

- `MAVEN_CENTRAL_USERNAME`
- `MAVEN_CENTRAL_PASSWORD`
- `SIGNING_KEY`
- `SIGNING_PASSWORD`

The build uploads signed publications through the Sonatype Portal OSSRH Staging
API. Review the staged deployment in Portal and perform the final confirmation
manually. Do not add a remote dependency snippet to README until Central serves
all expected modules for the selected version.

## 5. Publish release metadata

- Create the signed or annotated `v<version>` tag from the verified commit.
- Publish release notes from the matching changelog section, including device
  and performance evidence and any API or migration impact.
- Verify each README dependency snippet in a clean consumer build.
- Reset `CHANGELOG.md` links so `Unreleased` compares the new tag with `HEAD`.

## Failed or partial releases

Do not reuse a version that was partially visible to consumers. Close or drop a
staged deployment when Portal still permits it; otherwise publish a corrective
patch version. Record the affected coordinates and workaround in the changelog.
Never overwrite or silently replace a published release artifact.
