# Maven Central publishing and Kotlin Multiplatform — design

## Goal

Get unionKt listed on [klibs.io](https://klibs.io/faq), which requires:

1. an open-source GitHub project — already true;
2. at least one artifact on **Maven Central** — today only JitPack;
3. at least one artifact that is **Kotlin Multiplatform** (ships `kotlin-tooling-metadata.json`) —
   today everything is JVM-only;
4. a POM linking to the GitHub repository — already true.

Beyond the listing requirement, the generated code must genuinely work on every target the
library advertises.

## Decisions

- **Coordinates:** group `io.github.fcat97.unionkt` (the verified Central namespace is
  `io.github.fcat97`), artifacts `annotations`, `processor`, `processor-api`,
  `serialization-kotlinx`. First Central release: **0.5.0**. Coordinates on Central are permanent.
- **Targets:** all common Kotlin targets, including serialization on JS.
- **Apple targets** are built on GitHub Actions macOS runners; Windows (`mingwX64`) is built on
  Linux as well.
- **JitPack** stops at 0.4.0: it builds on Linux and cannot produce Apple targets. Old JitPack
  versions keep working.

## Feasibility (spike, 2026-09-23)

A throwaway Kotlin Multiplatform project (Kotlin 2.4.20, KSP 2.3.12, kotlinx-serialization 1.11.0)
consumed the 0.4.0 processor and serialization extension from `mavenLocal`, with KSP applied per
target (`kspJvm`, `kspJs`, `kspLinuxX64`, `kspMingwX64`). Common tests covered unions, `fold`,
accessors, generics, flattening, `@Derive` (including `Tagged<T, *>`), serialization round trips,
the `"5"`-stays-a-string case and the no-match message.

Result after the three fixes of §2.1 (applied as spike patches): **6/6 tests pass on JVM, JS (Node)
and Linux native; Windows compiles.** Without them, JS failed to compile on `@JvmName` and
`qualifiedName`, and every target failed on the no-argument `@Union` of §2.1.3.

## 1. Modules and publishing

| Module | Build | Published |
| --- | --- | --- |
| `annotations` | **Kotlin Multiplatform** (targets below) | yes |
| `processor` | JVM (KSP processors always run on the JVM) | yes |
| `processor-api` | JVM | yes |
| `serialization-kotlinx` | JVM | yes |
| `processor-tests`, `serialization-kotlinx-tests` | JVM, unchanged | no |
| `kmp-tests` (new) | Kotlin Multiplatform | no |
| `sample` | JVM, unchanged | no |

### 1.1 `annotations` targets

`jvm`, `js` (browser and Node), `wasmJs`, `wasmWasi`, `iosX64`, `iosArm64`, `iosSimulatorArm64`,
`macosX64`, `macosArm64`, `watchosArm32`, `watchosArm64`, `watchosX64`,
`watchosSimulatorArm64`, `watchosDeviceArm64`, `tvosX64`, `tvosArm64`, `tvosSimulatorArm64`,
`linuxX64`, `linuxArm64`, `mingwX64`, `androidNativeArm32`, `androidNativeArm64`,
`androidNativeX86`, `androidNativeX64`.

Android apps use the `jvm` artifact, as KSP processors' annotation libraries conventionally do.

A target that the Kotlin Gradle plugin 2.4.20 reports as deprecated or removed is dropped from this
list; the implementation records which ones, if any.

### 1.2 Publishing plugin

All four published modules use `com.vanniktech.maven.publish`, which handles signing, sources and
javadoc jars, the POM fields Central requires, and upload to the Central Portal:

- `coordinates("io.github.fcat97.unionkt", "<module>", version)`;
- POM: name, description, `url = https://github.com/fcat97/unionKt`, MIT license,
  developer `fcat97` / Shahriar Zaman, SCM URLs;
- `publishToMavenCentral()` **without automatic release** (§3.2);
- `signAllPublications()`.

The version comes from the Gradle property `releaseVersion`, defaulting to `0.5.0-SNAPSHOT` (not `VERSION_NAME`, which the publish plugin reads itself and would set a second time). The
JitPack environment handling in the root build and `jitpack.yml` are removed.

## 2. Generated code on every platform

### 2.1 Processor changes

1. **`@JvmName` only for the JVM.** `UnionProcessor` and `DeriveProcessor` read
   `SymbolProcessorEnvironment.platforms` and emit `@file:JvmName` and the per-function
   `@JvmName` only when some platform is a `JvmPlatformInfo`. That includes common-code
   processing for a project with a JVM target, where the annotation is legal. The clashes they
   prevent (facade class names, `List`/`MutableList` erasure) exist only on the JVM.
2. **Serializer error message:** `decoder::class.simpleName` instead of `qualifiedName`, which
   Kotlin/JS does not support.
3. **Missing `types` argument:** `KSAnnotation.memberTypes()` returns an empty list, not null,
   when KSP omits the argument (observed for `@Union` without arguments in a multiplatform
   compilation).

### 2.2 Consumer setup for multiplatform projects

Documented form: the processor (and optionally the serialization extension) added to each target's
KSP configuration, as in the spike:

```kotlin
dependencies {
    listOf("kspJvm", "kspJs", "kspIosArm64" /* … */).forEach {
        add(it, "io.github.fcat97.unionkt:processor:0.5.0")
    }
}
```

### 2.3 `:kmp-tests`

An unpublished Kotlin Multiplatform module consuming `project(":processor")` and
`project(":serialization-kotlinx")` through per-target KSP configurations, with
`project(":annotations")` and kotlinx-serialization-json in `commonMain`.

- **Targets:** `jvm`, `js` (Node), `linuxX64`, `mingwX64`, `iosSimulatorArm64`, `macosArm64`.
- **Declarations** in `commonMain`: a union, a generic union, a flattened union, a `@Derive` sealed
  type with an object case, a generic `@Derive` type with a star-projected case, `@Serializable`
  member classes.
- **Tests** in `commonTest`: constructor functions, `fold`, accessors, generics, flattening,
  `@Derive` (including star projections), serialization round trips (including a union as a
  property), the `"5"` case, the no-match message.
- **A JVM-only check** in `jvmTest` that reads the generated JS and Linux sources and asserts they
  contain no `JvmName`.
- On Linux, the Kotlin Gradle plugin skips the Apple targets with a warning, so `./gradlew build`
  runs the JVM, JS and Linux tests and compiles Windows.

## 3. CI and releases

### 3.1 `ci.yml` (push and pull request)

- **ubuntu-latest:** `./gradlew build` — every JVM suite plus `:kmp-tests` on JVM, JS and Linux,
  and the Windows compile.
- **macos-latest:** `./gradlew :annotations:compileKotlinIosArm64 :kmp-tests:iosSimulatorArm64Test
  :kmp-tests:macosArm64Test`.

### 3.2 `release.yml` (push of a tag matching `[0-9]+.[0-9]+.[0-9]+`)

One **macos-latest** job builds every target and runs
`./gradlew publishToMavenCentral -PreleaseVersion=<tag>`. A single job keeps each version one
Central deployment.

The deployment is uploaded and validated, then **released manually** with "Publish" in the Central
Portal: coordinates are permanent, so the first releases get a human check. Switching to
`publishToMavenCentral(automaticRelease = true)` later is a one-line change.

### 3.3 Secrets (provided by the owner)

| GitHub secret | Content |
| --- | --- |
| `MAVEN_CENTRAL_USERNAME` | Central Portal user-token username |
| `MAVEN_CENTRAL_PASSWORD` | Central Portal user-token password |
| `SIGNING_KEY` | ASCII-armored GPG private key |
| `SIGNING_KEY_PASSWORD` | its passphrase |

The workflow maps them to `ORG_GRADLE_PROJECT_mavenCentralUsername`,
`ORG_GRADLE_PROJECT_mavenCentralPassword`, `ORG_GRADLE_PROJECT_signingInMemoryKey` and
`ORG_GRADLE_PROJECT_signingInMemoryKeyPassword`. The GPG public key must be on a public keyserver
(`keyserver.ubuntu.com` or `keys.openpgp.org`).

## 4. Documentation

After 0.5.0 is on Central, the README:

- replaces the JitPack badge and setup with Maven Central (`mavenCentral()` only);
- uses the new coordinates;
- adds the multiplatform KSP setup of §2.2.

The klibs.io indexing request is filed only if the owner asks.

## 5. Out of scope

- Android-specific artifacts (AAR); Android uses the JVM artifact.
- Automatic Central release (§3.2).
- Publishing the processor or extensions as multiplatform (not possible for KSP processors).
