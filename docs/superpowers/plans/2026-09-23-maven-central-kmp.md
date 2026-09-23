# Maven Central and Kotlin Multiplatform Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `annotations` Kotlin Multiplatform, make generated code platform-neutral, publish all artifacts to Maven Central as `io.github.fcat97.unionkt`, and add CI and release workflows.

**Architecture:** The processors learn from KSP whether they target the JVM and only then emit `@JvmName`. `annotations` moves to `commonMain` under the Kotlin Multiplatform plugin. All published modules switch from `maven-publish` + JitPack to `com.vanniktech.maven.publish` 0.37.0, configured once in the root build. A new `:kmp-tests` module exercises every feature from common code. GitHub Actions runs CI on Linux and macOS and publishes from one macOS job on version tags.

**Tech Stack:** Kotlin 2.4.20, KSP 2.3.12, KotlinPoet 2.4.0, kotlinx-serialization 1.11.0, `com.vanniktech.maven.publish` 0.37.0, GitHub Actions.

**Spec:** `docs/superpowers/specs/2026-09-23-maven-central-kmp-design.md` — read it before starting any task.

## Global Constraints

- Group `io.github.fcat97.unionkt`; artifacts `annotations`, `processor`, `processor-api`, `serialization-kotlinx`; version from Gradle property `VERSION_NAME`, default `0.5.0-SNAPSHOT`.
- `annotations` targets (spec §1.1 minus the three Kotlin 2.4.20 reports as deprecated — `macosX64`, `watchosX64`, `tvosX64`, verified by a probe build on 2026-09-23): `jvm`, `js`, `wasmJs`, `wasmWasi`, `iosX64`, `iosArm64`, `iosSimulatorArm64`, `macosArm64`, `watchosArm32`, `watchosArm64`, `watchosSimulatorArm64`, `watchosDeviceArm64`, `tvosArm64`, `tvosSimulatorArm64`, `linuxX64`, `linuxArm64`, `mingwX64`, `androidNativeArm32`, `androidNativeArm64`, `androidNativeX86`, `androidNativeX64`.
- `publishToMavenCentral()` without automatic release; signing only when `signingInMemoryKey` is provided.
- No KSP API newer than 2.3.0; single-argument `validate()` stays.
- Existing suites stay green (processor-tests, serialization-kotlinx-tests, sample).
- Commit author `Shahriar Zaman <nayeem.zxc@gmail.com>` (configured repo-locally). **No `Co-Authored-By` trailer.** Do not push; the owner decides when.
- Run Gradle from `/home/portonics/development/project/unionKt`.

---

### Task 1: Platform-neutral generated code

**Files:**
- Modify: `processor/src/main/kotlin/com/github/fcat97/unionkt/processor/MemberResolver.kt`
- Modify: `processor/src/main/kotlin/com/github/fcat97/unionkt/processor/UnionWriter.kt`
- Modify: `processor/src/main/kotlin/com/github/fcat97/unionkt/processor/DeriveWriter.kt`
- Modify: `processor/src/main/kotlin/com/github/fcat97/unionkt/processor/UnionProcessor.kt`
- Modify: `processor/src/main/kotlin/com/github/fcat97/unionkt/processor/DeriveProcessor.kt`
- Modify: `processor/src/main/kotlin/com/github/fcat97/unionkt/processor/UnionProcessorProvider.kt`
- Modify: `processor/src/main/kotlin/com/github/fcat97/unionkt/processor/DeriveProcessorProvider.kt`
- Modify: `serialization-kotlinx/src/main/kotlin/com/github/fcat97/unionkt/serialization/kotlinx/SerializerWriter.kt`
- Test: `processor-tests/src/test/kotlin/com/github/fcat97/unionkt/processor/UnionGenericsTest.kt`

**Interfaces:**
- Produces: `UnionWriter(codeGenerator: CodeGenerator, emitJvmNames: Boolean)`, `DeriveWriter(codeGenerator: CodeGenerator, emitJvmNames: Boolean)`, `UnionProcessor(codeGenerator, logger, extensions = loadExtensions(), emitJvmNames: Boolean = true)`, `DeriveProcessor(codeGenerator, logger, emitJvmNames: Boolean = true)`, `internal fun SymbolProcessorEnvironment.targetsJvm(): Boolean`.

The `@JvmName` gating cannot be observed here (kotlin-compile-testing only runs KSP for the JVM); `:kmp-tests` asserts it in Task 3.

- [ ] **Step 1: Write the failing test for an omitted `types` argument**

Add this test to `UnionGenericsTest`:

```kotlin
    @Test
    fun `a @Union whose types argument KSP omits is treated as having no types`() {
        // Declaring the annotation in source (as the spike's multiplatform stand-in did) makes
        // KSP omit the defaulted vararg instead of passing an empty list.
        compileWithUnionProcessor(
            SourceFile.kotlin(
                "Union.kt",
                """
                package com.github.fcat97.unionkt

                import kotlin.reflect.KClass

                @Target(AnnotationTarget.CLASS)
                @Retention(AnnotationRetention.BINARY)
                annotation class Union(vararg val types: KClass<*>)
                """.trimIndent(),
            ),
            SourceFile.kotlin(
                "Either.kt",
                """
                package test

                import com.github.fcat97.unionkt.Union

                @Union
                interface EitherSpec<L, R>

                fun left(): Either<String, Int> = Either.onL("e")
                """.trimIndent(),
            ),
        ).assertSucceeded()
    }
```

- [ ] **Step 2: Run it**

Run: `./gradlew :processor-tests:test --tests '*UnionGenericsTest*' --console=plain`
Expected: the new test FAILS with `Unable to read the 'types' argument of @Union on 'EitherSpec'`. If it instead fails on a redeclaration of `Union`, or passes, the source-declared annotation does not reproduce KSP's omission in this harness: delete the test, note it in the commit message, and rely on `:kmp-tests` (Task 3), whose generic union exercises the same path.

- [ ] **Step 3: Treat a missing `types` argument as empty**

In `MemberResolver.kt`, in `KSAnnotation.memberTypes()`, change

```kotlin
    val argument = arguments.firstOrNull { it.name?.asString() == TYPES_ARGUMENT } ?: return null
```

to

```kotlin
    // KSP omits a defaulted vararg in some compilations (seen in multiplatform builds): no
    // argument means no types, not an unreadable annotation.
    val argument = arguments.firstOrNull { it.name?.asString() == TYPES_ARGUMENT } ?: return emptyList()
```

- [ ] **Step 4: Gate `@JvmName` on the JVM**

In `UnionProcessorProvider.kt`, add the import `com.google.devtools.ksp.processing.JvmPlatformInfo` and, after `registerForNewFeaturesIfSupported`, this function:

```kotlin
/**
 * Whether the code being generated is compiled for the JVM (alone, or as part of common code
 * shared with a JVM target). `@JvmName` exists only there.
 */
internal fun SymbolProcessorEnvironment.targetsJvm(): Boolean = platforms.any { it is JvmPlatformInfo }
```

In the same file, pass it to the processor:

```kotlin
        val processor = UnionProcessor(
            codeGenerator = environment.codeGenerator,
            logger = environment.logger,
            emitJvmNames = environment.targetsJvm(),
        )
```

In `DeriveProcessorProvider.kt`:

```kotlin
        val processor = DeriveProcessor(
            codeGenerator = environment.codeGenerator,
            logger = environment.logger,
            emitJvmNames = environment.targetsJvm(),
        )
```

In `UnionProcessor.kt`, change the constructor and writer construction to:

```kotlin
internal class UnionProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
    private val extensions: List<UnionExtension> = loadExtensions(),
    emitJvmNames: Boolean = true,
) : SymbolProcessor {

    private val memberResolver = MemberResolver(logger)
    private val writer = UnionWriter(codeGenerator, emitJvmNames)
```

In `DeriveProcessor.kt`:

```kotlin
internal class DeriveProcessor(
    codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
    emitJvmNames: Boolean = true,
) : SymbolProcessor {

    private val writer = DeriveWriter(codeGenerator, emitJvmNames)
```

In `UnionWriter.kt`:
1. Change the class header to `internal class UnionWriter(private val codeGenerator: CodeGenerator, private val emitJvmNames: Boolean) {`.
2. Replace the file-level annotation block

```kotlin
            .addAnnotation(
                AnnotationSpec.builder(JvmName::class)
                    .useSiteTarget(AnnotationSpec.UseSiteTarget.FILE)
                    .addMember("%S", model.unionType.simpleName + "UnionKt")
                    .build(),
            )
```

with

```kotlin
            .apply {
                if (emitJvmNames) {
                    addAnnotation(
                        AnnotationSpec.builder(JvmName::class)
                            .useSiteTarget(AnnotationSpec.UseSiteTarget.FILE)
                            .addMember("%S", model.unionType.simpleName + "UnionKt")
                            .build(),
                    )
                }
            }
```

and update the comment above it to end with: `Only on the JVM: facade classes exist nowhere else.`
3. In `constructorFunctions`, replace

```kotlin
                .addAnnotation(
                    AnnotationSpec.builder(JvmName::class)
                        .addMember("%S", model.unionType.simpleName + "Of" + member.simpleName)
                        .build(),
                )
```

with

```kotlin
                .apply {
                    if (emitJvmNames) {
                        addAnnotation(
                            AnnotationSpec.builder(JvmName::class)
                                .addMember("%S", model.unionType.simpleName + "Of" + member.simpleName)
                                .build(),
                        )
                    }
                }
```

In `DeriveWriter.kt`:
1. Change the class header to `internal class DeriveWriter(private val codeGenerator: CodeGenerator, private val emitJvmNames: Boolean) {`.
2. Replace

```kotlin
            .addAnnotation(
                AnnotationSpec.builder(JvmName::class)
                    .useSiteTarget(AnnotationSpec.UseSiteTarget.FILE)
                    .addMember("%S", model.fileName + "Kt")
                    .build(),
            )
```

with

```kotlin
            .apply {
                if (emitJvmNames) {
                    addAnnotation(
                        AnnotationSpec.builder(JvmName::class)
                            .useSiteTarget(AnnotationSpec.UseSiteTarget.FILE)
                            .addMember("%S", model.fileName + "Kt")
                            .build(),
                    )
                }
            }
```

and change the comment above it to `// A distinctive facade name, so it cannot clash with a user file of the same name. JVM only.`

- [ ] **Step 5: Make the serializer's error message JS-compatible**

In `SerializerWriter.kt`, change `decoder::class.qualifiedName` to `decoder::class.simpleName` (Kotlin/JS does not support `qualifiedName`).

- [ ] **Step 6: Run the tests and the full build**

Run: `./gradlew :processor-tests:test --tests '*UnionGenericsTest*' --console=plain` — Expected: PASS (all generics tests, including the new one if kept).
Run: `./gradlew build --console=plain` — Expected: `BUILD SUCCESSFUL`; existing assertions on `@JvmName` still pass, since kotlin-compile-testing reports a JVM platform.

- [ ] **Step 7: Commit**

```bash
git add processor serialization-kotlinx processor-tests
git commit -m "Make generated code platform-neutral

Emit @JvmName only when KSP targets the JVM, use simpleName instead of
the JS-unsupported qualifiedName in the serializer, and treat an omitted
@Union types argument as empty. All three surfaced in the multiplatform
spike."
```

---

### Task 2: Multiplatform `annotations` and Maven Central publishing

**Files:**
- Modify: `gradle/libs.versions.toml`, `build.gradle.kts` (root), `gradle.properties`
- Modify: `annotations/build.gradle.kts`; move `annotations/src/main/kotlin/...` → `annotations/src/commonMain/kotlin/...`
- Modify: `processor/build.gradle.kts`, `processor-api/build.gradle.kts`, `serialization-kotlinx/build.gradle.kts`
- Delete: `jitpack.yml`

**Interfaces:**
- Produces: plugin aliases `libs.plugins.kotlin.multiplatform`, `libs.plugins.maven.publish`; publications `io.github.fcat97.unionkt:{annotations,processor,processor-api,serialization-kotlinx}:$VERSION_NAME`.

- [ ] **Step 1: Catalog, root build and properties**

In `gradle/libs.versions.toml`: under `[versions]` add `mavenPublish = "0.37.0"`; under `[plugins]` add

```toml
kotlin-multiplatform = { id = "org.jetbrains.kotlin.multiplatform", version.ref = "kotlin" }
maven-publish = { id = "com.vanniktech.maven.publish", version.ref = "mavenPublish" }
```

Replace the root `build.gradle.kts` with:

```kotlin
import com.vanniktech.maven.publish.MavenPublishBaseExtension

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.maven.publish) apply false
}

// Published as io.github.fcat97.unionkt:<module>:<version>. The release workflow passes
// -PVERSION_NAME=<tag>; local builds are snapshots.
allprojects {
    group = "io.github.fcat97.unionkt"
    version = providers.gradleProperty("VERSION_NAME").getOrElse("0.5.0-SNAPSHOT")
}

// Shared Maven Central setup for every module that applies com.vanniktech.maven.publish.
// Each module sets its own `description` and platform (JVM or multiplatform).
subprojects {
    plugins.withId("com.vanniktech.maven.publish") {
        extensions.configure<MavenPublishBaseExtension> {
            coordinates(project.group.toString(), project.name, project.version.toString())

            // Uploaded and validated, then released by hand in the Central Portal.
            publishToMavenCentral()

            // Central requires signatures. Local publishing (no key) stays unsigned.
            if (providers.gradleProperty("signingInMemoryKey").isPresent) {
                signAllPublications()
            }

            pom {
                name.set("unionKt ${project.name}")
                description.set(provider { project.description })
                inceptionYear.set("2026")
                url.set("https://github.com/fcat97/unionKt")
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                        distribution.set("https://opensource.org/licenses/MIT")
                    }
                }
                developers {
                    developer {
                        id.set("fcat97")
                        name.set("Shahriar Zaman")
                        url.set("https://github.com/fcat97")
                    }
                }
                scm {
                    url.set("https://github.com/fcat97/unionKt")
                    connection.set("scm:git:https://github.com/fcat97/unionKt.git")
                    developerConnection.set("scm:git:ssh://git@github.com/fcat97/unionKt.git")
                }
            }
        }
    }
}
```

Append to `gradle.properties`:

```properties

# Apple targets cannot be built on Linux; skip them there without a warning per task.
kotlin.native.ignoreDisabledTargets=true
```

Delete `jitpack.yml`: `git rm jitpack.yml`.

- [ ] **Step 2: Make `annotations` multiplatform**

```bash
mkdir -p annotations/src/commonMain/kotlin/com/github/fcat97/unionkt
git mv annotations/src/main/kotlin/com/github/fcat97/unionkt/Union.kt annotations/src/commonMain/kotlin/com/github/fcat97/unionkt/Union.kt
git mv annotations/src/main/kotlin/com/github/fcat97/unionkt/Derive.kt annotations/src/commonMain/kotlin/com/github/fcat97/unionkt/Derive.kt
```

Replace `annotations/build.gradle.kts` with:

```kotlin
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinMultiplatform
import com.vanniktech.maven.publish.SourcesJar
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.maven.publish)
}

description = "The @Union and @Derive annotations read by the unionKt KSP processors."

// Every Kotlin target except those Kotlin 2.4.20 deprecates (macosX64, watchosX64, tvosX64).
// Android apps use the jvm artifact.
kotlin {
    jvm {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_17
        }
    }
    js {
        browser()
        nodejs()
    }
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
        nodejs()
    }
    @OptIn(ExperimentalWasmDsl::class)
    wasmWasi {
        nodejs()
    }

    iosX64()
    iosArm64()
    iosSimulatorArm64()
    macosArm64()
    watchosArm32()
    watchosArm64()
    watchosSimulatorArm64()
    watchosDeviceArm64()
    tvosArm64()
    tvosSimulatorArm64()

    linuxX64()
    linuxArm64()
    mingwX64()

    androidNativeArm32()
    androidNativeArm64()
    androidNativeX86()
    androidNativeX64()
}

mavenPublishing {
    configure(KotlinMultiplatform(javadocJar = JavadocJar.Empty(), sourcesJar = SourcesJar.Sources()))
}
```

- [ ] **Step 3: Switch the JVM modules to the new publishing setup**

In each of `processor/build.gradle.kts`, `processor-api/build.gradle.kts` and `serialization-kotlinx/build.gradle.kts`:
1. Replace `` `maven-publish` `` in `plugins { … }` with `alias(libs.plugins.maven.publish)`.
2. Delete `withSourcesJar()` from the `java { … }` block.
3. Delete the whole `publishing { … }` block.
4. Add these imports at the top (after the existing `JvmTarget` import):

```kotlin
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm
import com.vanniktech.maven.publish.SourcesJar
```

5. Add after the `plugins { … }` block:

```kotlin
description = "<module description, below>"
```

with, respectively:
- processor: `KSP processors that generate unions from @Union markers and helpers for @Derive sealed types.`
- processor-api: `Extension API for the unionKt KSP processor.`
- serialization-kotlinx: `unionKt extension that generates untagged kotlinx.serialization JSON serializers for unions.`

6. Append at the end of the file:

```kotlin
mavenPublishing {
    configure(KotlinJvm(javadocJar = JavadocJar.Empty(), sourcesJar = SourcesJar.Sources()))
}
```

- [ ] **Step 4: Run the full build**

Run: `./gradlew build --console=plain`
Expected: `BUILD SUCCESSFUL`; all existing suites pass (JVM consumers resolve the `annotations` JVM variant).

- [ ] **Step 5: Publish locally and inspect the artifacts**

Run: `./gradlew publishToMavenLocal --console=plain`
Then check:

```bash
ls ~/.m2/repository/io/github/fcat97/unionkt/
ls ~/.m2/repository/io/github/fcat97/unionkt/annotations/0.5.0-SNAPSHOT/
cat ~/.m2/repository/io/github/fcat97/unionkt/processor/0.5.0-SNAPSHOT/processor-0.5.0-SNAPSHOT.pom
```

Expected:
- modules `annotations`, `annotations-jvm`, `annotations-js`, `annotations-linuxx64`, `annotations-mingwx64`, … (Apple ones only on macOS), `processor`, `processor-api`, `serialization-kotlinx`;
- `annotations/0.5.0-SNAPSHOT/` contains `annotations-0.5.0-SNAPSHOT-kotlin-tooling-metadata.json` (the file klibs.io requires);
- the processor POM contains `<url>https://github.com/fcat97/unionKt</url>`, the MIT license, the developer and the SCM block; `-sources.jar` and `-javadoc.jar` exist beside each JVM jar.

Remove the snapshot afterwards: `rm -rf ~/.m2/repository/io/github/fcat97/unionkt`.

- [ ] **Step 6: Commit**

```bash
git add -A gradle/libs.versions.toml build.gradle.kts gradle.properties annotations processor/build.gradle.kts processor-api/build.gradle.kts serialization-kotlinx/build.gradle.kts jitpack.yml
git commit -m "Publish to Maven Central; make annotations multiplatform

Artifacts move to io.github.fcat97.unionkt and are published with
com.vanniktech.maven.publish (signed when a key is provided, released
by hand). annotations now targets every Kotlin platform except the
three Kotlin 2.4.20 deprecates. JitPack configuration is removed."
```

---

### Task 3: `:kmp-tests`

**Files:**
- Modify: `settings.gradle.kts`
- Create: `kmp-tests/build.gradle.kts`
- Create: `kmp-tests/src/commonMain/kotlin/kmptests/Model.kt`
- Create: `kmp-tests/src/commonTest/kotlin/kmptests/FeaturesTest.kt`
- Create: `kmp-tests/src/jvmTest/kotlin/kmptests/GeneratedCodeTest.kt`

- [ ] **Step 1: Create the module**

In `settings.gradle.kts`, after `include(":serialization-kotlinx-tests")`, add:

```kotlin
// Not published: exercises every feature from common code on each Kotlin platform.
include(":kmp-tests")
```

Create `kmp-tests/build.gradle.kts`:

```kotlin
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// NOT published. Compiles and tests the generated code on JVM, JS, Linux and Windows
// (compile only) here, and on the iOS simulator and macOS in CI.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

kotlin {
    jvm {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_17
        }
    }
    js {
        nodejs()
    }
    linuxX64()
    mingwX64()
    iosSimulatorArm64()
    macosArm64()

    sourceSets {
        commonMain.dependencies {
            implementation(project(":annotations"))
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

// KSP per target, as the README documents for multiplatform users.
dependencies {
    listOf("kspJvm", "kspJs", "kspLinuxX64", "kspMingwX64", "kspIosSimulatorArm64", "kspMacosArm64").forEach {
        add(it, project(":processor"))
        add(it, project(":serialization-kotlinx"))
    }
}

// GeneratedCodeTest reads the JS and Linux KSP output.
tasks.named("jvmTest") {
    dependsOn("kspKotlinJs", "kspKotlinLinuxX64")
}
```

- [ ] **Step 2: Write the declarations and tests**

Create `kmp-tests/src/commonMain/kotlin/kmptests/Model.kt`:

```kotlin
package kmptests

import com.github.fcat97.unionkt.Derive
import com.github.fcat97.unionkt.Union
import kotlinx.serialization.Serializable

@Serializable
data class User(val name: String)

@Serializable
data class Circle(val radius: Int)

@Serializable
data class Square(val side: Int)

@Union(Int::class, String::class, User::class)
interface ResultSpec

@Union
interface EitherSpec<L, R>

@Union(Circle::class, Square::class)
interface ShapeSpec

@Union(Int::class, ShapeSpec::class)
interface ItemSpec

@Derive
sealed interface UiState {
    data object Loading : UiState
    data class Loaded(val count: Int) : UiState
}

@Derive
sealed interface Box<out T> {
    data class Tagged<out T, out Tag>(val value: T, val tag: Tag) : Box<T>
    data class Many<out E>(val items: List<E>) : Box<List<E>>
}
```

Create `kmp-tests/src/commonTest/kotlin/kmptests/FeaturesTest.kt`:

```kotlin
package kmptests

import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Serializable
data class Holder(val e: Either<String, Int>)

/** Every feature, from common code, on every target this module builds. */
class FeaturesTest {

    @Test
    fun unionsHaveConstructorsFoldAndAccessors() {
        assertEquals(Result.OnInt(5), Result(5))
        assertEquals(6, Result(5).fold(onInt = { it + 1 }, onString = { 0 }, onUser = { 0 }))
        assertTrue(Result("x").isString)
        assertEquals("x", Result("x").stringOrNull)
        assertNull(Result("x").intOrNull)
    }

    @Test
    fun genericUnionsNeedNoCasts() {
        val e: Either<String, Int> = Either.onR(1)
        assertEquals(1, e.rOrNull)
        assertEquals("r1", e.fold(onL = { "l$it" }, onR = { "r$it" }))
    }

    @Test
    fun flattenedUnionsConvert() {
        assertEquals(Item.OnSquare(Square(2)), Shape(Square(2)).toItem())
    }

    @Test
    fun deriveWorksIncludingStarProjections() {
        assertEquals("loading", UiState.Loading.fold(onLoading = { "loading" }, onLoaded = { "loaded" }))
        val loaded: UiState = UiState.Loaded(3)
        assertEquals(3, loaded.loadedOrNull?.count)
        val box: Box<Int> = Box.Tagged(1, "t")
        assertEquals(1, box.fold(onTagged = { it.value }, onMany = { 0 }))
        assertEquals("t", box.taggedOrNull?.tag)
    }

    @Test
    fun serializationRoundTrips() {
        assertEquals("5", Json.encodeToString<Result>(Result(5)))
        assertEquals(Result("hi"), Json.decodeFromString<Result>("\"hi\""))
        assertEquals("{\"e\":5}", Json.encodeToString(Holder(Either.onR(5))))
        assertEquals(Holder(Either.onL("x")), Json.decodeFromString<Holder>("{\"e\":\"x\"}"))
        assertEquals(Item(Square(2)), Json.decodeFromString<Item>("{\"side\":2}"))
    }

    @Test
    fun aQuotedNumberStaysAString() {
        assertEquals(Result("5"), Json.decodeFromString<Result>("\"5\""))
        assertEquals(Result(5), Json.decodeFromString<Result>("5"))
    }

    @Test
    fun unmatchedInputFailsClearly() {
        val error = assertFailsWith<SerializationException> { Json.decodeFromString<Result>("[1]") }
        assertTrue("Cannot decode an array as Result" in error.message.orEmpty(), error.message)
    }
}
```

Create `kmp-tests/src/jvmTest/kotlin/kmptests/GeneratedCodeTest.kt`:

```kotlin
package kmptests

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** `@JvmName` must reach JVM output only (spec §2.1). Reads this module's KSP output. */
class GeneratedCodeTest {

    private fun kotlinFiles(dir: String): List<File> {
        val root = File(dir)
        assertTrue(root.isDirectory, "no KSP output at $root")
        return root.walkTopDown().filter { it.extension == "kt" }.toList()
    }

    @Test
    fun jsAndNativeOutputHasNoJvmName() {
        val offenders = (kotlinFiles("build/generated/ksp/js/jsMain/kotlin") +
            kotlinFiles("build/generated/ksp/linuxX64/linuxX64Main/kotlin"))
            .filter { "JvmName" in it.readText() }
        assertTrue(offenders.isEmpty(), "JvmName found in $offenders")
    }

    @Test
    fun jvmOutputKeepsJvmName() {
        val result = kotlinFiles("build/generated/ksp/jvm/jvmMain/kotlin").single { it.name == "Result.kt" }
        assertTrue("@file:JvmName(\"ResultUnionKt\")" in result.readText(), result.readText())
    }
}
```

- [ ] **Step 3: Run the module's tests**

Run: `./gradlew :kmp-tests:jvmTest :kmp-tests:jsNodeTest :kmp-tests:linuxX64Test :kmp-tests:compileKotlinMingwX64 --console=plain`
Expected: `BUILD SUCCESSFUL`; JVM runs 9 tests (7 common + 2 generated-code), JS and Linux run 7 each. If `jsAndNativeOutputHasNoJvmName` fails, Task 1's gating is wrong — fix it there.

- [ ] **Step 4: Run the full build**

Run: `./gradlew build --console=plain`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add settings.gradle.kts kmp-tests
git commit -m "Test every feature from common code on each platform

:kmp-tests consumes the processor and serialization extension per KSP
target and runs the same common tests on JVM, JS and Linux (Windows
compiles; Apple targets run in CI), plus a check that @JvmName reaches
JVM output only."
```

---

### Task 4: GitHub Actions

**Files:**
- Create: `.github/workflows/ci.yml`
- Create: `.github/workflows/release.yml`

- [ ] **Step 1: Create `ci.yml`**

```yaml
name: CI

on:
  push:
    branches: [main]
  pull_request:

jobs:
  linux:
    name: Linux (JVM, JS, Linux, Windows compile)
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 17
      - uses: gradle/actions/setup-gradle@v4
      - name: Cache Kotlin/Native toolchain
        uses: actions/cache@v4
        with:
          path: ~/.konan
          key: konan-${{ runner.os }}-${{ hashFiles('gradle/libs.versions.toml') }}
      - run: ./gradlew build

  macos:
    name: macOS (Apple targets)
    runs-on: macos-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 17
      - uses: gradle/actions/setup-gradle@v4
      - name: Cache Kotlin/Native toolchain
        uses: actions/cache@v4
        with:
          path: ~/.konan
          key: konan-${{ runner.os }}-${{ hashFiles('gradle/libs.versions.toml') }}
      - run: ./gradlew :annotations:compileKotlinIosArm64 :kmp-tests:iosSimulatorArm64Test :kmp-tests:macosArm64Test
```

- [ ] **Step 2: Create `release.yml`**

```yaml
name: Release

on:
  push:
    tags:
      - '[0-9]+.[0-9]+.[0-9]+'

jobs:
  publish:
    name: Publish to Maven Central
    # One macOS job builds every target (Apple, Linux, Windows, JS, Wasm, JVM), so each version
    # is a single Central deployment.
    runs-on: macos-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 17
      - uses: gradle/actions/setup-gradle@v4
      - name: Cache Kotlin/Native toolchain
        uses: actions/cache@v4
        with:
          path: ~/.konan
          key: konan-${{ runner.os }}-${{ hashFiles('gradle/libs.versions.toml') }}
      - name: Publish
        run: ./gradlew publishToMavenCentral -PVERSION_NAME=${{ github.ref_name }} --no-configuration-cache
        env:
          ORG_GRADLE_PROJECT_mavenCentralUsername: ${{ secrets.MAVEN_CENTRAL_USERNAME }}
          ORG_GRADLE_PROJECT_mavenCentralPassword: ${{ secrets.MAVEN_CENTRAL_PASSWORD }}
          ORG_GRADLE_PROJECT_signingInMemoryKey: ${{ secrets.SIGNING_KEY }}
          ORG_GRADLE_PROJECT_signingInMemoryKeyPassword: ${{ secrets.SIGNING_KEY_PASSWORD }}
```

- [ ] **Step 3: Validate the YAML**

Run: `python3 -c "import yaml,sys; [yaml.safe_load(open(f)) for f in sys.argv[1:]]; print('ok')" .github/workflows/ci.yml .github/workflows/release.yml`
Expected: `ok`. (The workflows themselves run only on GitHub; the owner decides when to push.)

- [ ] **Step 4: Confirm the release task resolves locally**

Run: `./gradlew publishToMavenCentral --dry-run --console=plain | grep -E "publish.*MavenCentral|BUILD"`
Expected: task names for every module's Maven Central publication and `BUILD SUCCESSFUL` (nothing is uploaded with `--dry-run`).

- [ ] **Step 5: Commit**

```bash
git add .github
git commit -m "Add CI and release workflows

CI builds and tests on Linux (JVM, JS, Linux, Windows compile) and
macOS (Apple targets). Pushing a version tag publishes every target
from one macOS job to Maven Central, to be released in the portal."
```
