# Publishing sdui-kmp

sdui-kmp ships as a set of `dev.sdui.kmp:<module>:<version>` Maven artifacts. The wiring
lives in the `sdui.publish` convention plugin (`build-logic/src/main/kotlin/sdui.publish.gradle.kts`)
and is applied to every shipping module — see the [coordinates table](#artifact-coordinates).

## Local publish (the smoke test)

Single module:

```bash
./gradlew :runtime:publishToMavenLocal
```

All shipping modules in one go:

```bash
./gradlew publishAllToMavenLocal
```

Output lands in `~/.m2/repository/dev/sdui/kmp/<module>/<version>/`. Each KMP module produces
the multiplatform "root" artifact plus per-target variants (`-jvm`, `-androidRelease`,
`-iosArm64`, `-iosX64`, `-iosSimulatorArm64`, `-wasmJs`); each emits POM, sources JAR, and
Javadoc JAR. Pure-JVM modules (`:server`, `:tooling-cli`, `:tooling-telemetry-otel`,
`:tooling-snapshot`) emit a single `<module>-<version>.jar` plus its sidecars.

A consumer integrates the local snapshot by adding `mavenLocal()` to their settings repos
and depending on the coordinates below.

## Sonatype Central onboarding (one-time)

Before the first Maven Central release, the project owner walks this checklist:

1. **Register the namespace.** Claim `dev.sdui.kmp` on the [Sonatype Central Portal](https://central.sonatype.com/)
   and verify ownership of `sdui-kmp.dev` (or whichever domain we end up using). Until the
   namespace is approved, releases will be rejected.
2. **Generate a GPG signing key.**
   ```bash
   gpg --gen-key                     # follow the prompts; pick a strong passphrase
   gpg --list-secret-keys --keyid-format short
   gpg --keyserver keyserver.ubuntu.com --send-keys <KEY-ID>
   ```
   Distributing the public key to a keyserver is required so Sonatype can verify signatures.
3. **Mint a Sonatype Central user token.** From the Central Portal account page, click
   "Generate User Token" and copy the username/password pair — this is what the release
   workflow authenticates with, not the web-UI login.
4. **Configure GitHub repository secrets** (Settings -> Secrets and variables -> Actions):

   | Secret name              | Value                                                                |
   | ------------------------ | -------------------------------------------------------------------- |
   | `MAVEN_CENTRAL_USERNAME` | Sonatype Central user-token name                                     |
   | `MAVEN_CENTRAL_PASSWORD` | Sonatype Central user-token secret                                   |
   | `GPG_PRIVATE_KEY`        | `gpg --armor --export-secret-keys <KEY-ID>` output, single-line OK   |
   | `GPG_PASSPHRASE`         | The passphrase used when generating the key                          |

   `gh secret set GPG_PRIVATE_KEY --body-file private.asc` is the safest way to upload the
   armored key — the GitHub UI will accept the multiline blob directly.
5. **Local-only fallback.** Developers cutting an emergency manual release outside CI can
   set the same values in `~/.gradle/gradle.properties`:
   ```properties
   signingInMemoryKey=<ASCII-armored secret key, single line with literal \n line breaks>
   signingInMemoryKeyPassword=<gpg passphrase>
   ```
   plus `MAVEN_CENTRAL_USERNAME` / `MAVEN_CENTRAL_PASSWORD` as environment variables.

## The release flow (automated)

The [`gradle-nexus-publish-plugin`](https://github.com/gradle-nexus/publish-plugin) v2 is
applied at the root project (see [`build.gradle.kts`](../build.gradle.kts)) and synthesises
two tasks:

* `publishToSonatype` — uploads every shipping module to a fresh Sonatype Central staging
  repository.
* `closeAndReleaseSonatypeStagingRepository` — closes the staging repo (running Sonatype's
  validation rules: signatures, POM metadata, Javadoc/sources presence), then promotes it
  to Maven Central in one step.

The plugin also auto-routes snapshot versions (any `sduiVersion` ending in `-SNAPSHOT`) to
`https://central.sonatype.com/repository/maven-snapshots/` instead of the staging endpoint
— no flag needed.

### Cutting a release (the happy path)

1. Bump `sduiVersion` in [`gradle.properties`](../gradle.properties) from
   `1.0.0-SNAPSHOT` to `1.0.0` (or whatever the next release is) and merge the bump.
2. Run the local pre-flight:
   ```bash
   ./gradlew check verifyDependencyRules verifyProtocolSnapshot verifyRelease
   ```
   `verifyRelease` materialises every module's POM via `publishAllToMavenLocal` and asserts
   the Sonatype-required fields (name, description, url, license, scm, developers) are
   present. Iterating on missing metadata locally is much faster than discovering it after
   a tag push.
3. Tag and push:
   ```bash
   git tag v1.0.0
   git push origin v1.0.0
   ```
4. The [`release.yml`](../.github/workflows/release.yml) workflow fires on the tag,
   rewrites `sduiVersion` from the tag name, runs `verifyRelease`, then runs
   `./gradlew publishToSonatype closeAndReleaseSonatypeStagingRepository` on a `macos-14`
   runner (full Xcode, needed for the iOS KMP variants).
5. After the workflow goes green, the artifacts appear on Maven Central within ~30 minutes.
6. Bump `sduiVersion` to the next `-SNAPSHOT` (e.g. `1.1.0-SNAPSHOT`) and commit.

### Cutting a SNAPSHOT (CI nightly)

Snapshots are wired identically — push a commit with `sduiVersion=1.1.0-SNAPSHOT` and run
`./gradlew publishToSonatype` (or invoke the release workflow against a snapshot ref).
The plugin sees the `-SNAPSHOT` suffix and routes the upload to the snapshot repository,
skipping the staging-and-close dance.

### Manual fallback

If the GitHub Actions runner is unavailable and the maintainer has the secrets locally:

```bash
export MAVEN_CENTRAL_USERNAME=<token-name>
export MAVEN_CENTRAL_PASSWORD=<token-secret>
export ORG_GRADLE_PROJECT_signingInMemoryKey="$(cat private.asc)"
export ORG_GRADLE_PROJECT_signingInMemoryKeyPassword=<passphrase>

./gradlew verifyRelease
./gradlew publishToSonatype closeAndReleaseSonatypeStagingRepository
```

## Artifact coordinates

Every artifact lives under group `dev.sdui.kmp`. Today's `sduiVersion` is `1.0.0-SNAPSHOT`.

| Module                  | Coordinates                                          | Notes                                |
| ----------------------- | ---------------------------------------------------- | ------------------------------------ |
| `:protocol`             | `dev.sdui.kmp:protocol:VERSION`                      | KMP — Android, iOS, JVM, Wasm        |
| `:protocol-fixtures`    | `dev.sdui.kmp:protocol-fixtures:VERSION`             | KMP                                  |
| `:runtime`              | `dev.sdui.kmp:runtime:VERSION`                       | KMP, depends on Compose Multiplatform |
| `:server`               | `dev.sdui.kmp:server:VERSION`                        | JVM only (Kotlin server DSL)         |
| `:widgets-core`         | `dev.sdui.kmp:widgets-core:VERSION`                  | KMP, depends on `:runtime`           |
| `:widgets-forms`        | `dev.sdui.kmp:widgets-forms:VERSION`                 | KMP                                  |
| `:widgets-media`        | `dev.sdui.kmp:widgets-media:VERSION`                 | KMP                                  |
| `:widgets-media-coil`   | `dev.sdui.kmp:widgets-media-coil:VERSION`            | KMP, opt-in Coil 3 implementation    |
| `:widgets-nav`          | `dev.sdui.kmp:widgets-nav:VERSION`                   | KMP                                  |
| `:transport-http`       | `dev.sdui.kmp:transport-http:VERSION`                | KMP, Ktor 3 client                   |
| `:transport-live`       | `dev.sdui.kmp:transport-live:VERSION`                | KMP, Ktor 3 WebSockets               |
| `:transport-cache`      | `dev.sdui.kmp:transport-cache:VERSION`               | KMP                                  |
| `:tooling-cli`          | `dev.sdui.kmp:tooling-cli:VERSION`                   | JVM, has `application` distribution  |
| `:tooling-telemetry`    | `dev.sdui.kmp:tooling-telemetry:VERSION`             | KMP                                  |
| `:tooling-telemetry-otel` | `dev.sdui.kmp:tooling-telemetry-otel:VERSION`      | JVM, OpenTelemetry adapter           |
| `:tooling-snapshot`     | `dev.sdui.kmp:tooling-snapshot:VERSION`              | JVM, golden-snapshot harness         |

Sample modules (`:samples:*`), `:tooling-preview`, and `:benchmarks` are not published.

## Consuming the framework

```kotlin
// build.gradle.kts of a downstream Compose Multiplatform module
dependencies {
    implementation("dev.sdui.kmp:runtime:1.0.0")
    implementation("dev.sdui.kmp:widgets-core:1.0.0")
    implementation("dev.sdui.kmp:widgets-forms:1.0.0")
    implementation("dev.sdui.kmp:transport-http:1.0.0")
    // Server side:
    // implementation("dev.sdui.kmp:server:1.0.0")
}
```

The KMP modules expose their per-target variants via Gradle Module Metadata, so a
multiplatform consumer just declares the common dependency once.

## Troubleshooting

* **`closeAndReleaseSonatypeStagingRepository` fails with "no staging profile found".** The
  Sonatype namespace claim has not been approved yet, or the user token is wrong. The
  workflow leaves the staging repo behind — clean it up via the Central Portal UI before
  retrying.
* **Signature validation fails.** The public half of the signing key has not been
  distributed to a keyserver yet. Run `gpg --keyserver keyserver.ubuntu.com --send-keys <KEY-ID>`
  and re-trigger the workflow (push a new tag — Sonatype rejects re-uploads to a closed
  staging repo, so a v1.0.1 tag is the cleanest recovery path).
* **`verifyRelease` fails locally.** A POM is missing one of name / description / url /
  licenses / scm / developers. Inspect the failing `.pom` under
  `~/.m2/repository/dev/sdui/kmp/<module>/<version>/` and patch
  `build-logic/src/main/kotlin/sdui.publish.gradle.kts` accordingly.
