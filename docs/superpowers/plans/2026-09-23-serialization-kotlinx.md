# Extension API and kotlinx.serialization Plugin Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a public extension API to the unionKt processor and ship the first extension, which attaches an untagged JSON kotlinx.serialization serializer to every union.

**Architecture:** A new `:processor-api` module defines `UnionExtension` plus a read-only `UnionInfo`. `:processor` loads extensions with `ServiceLoader`, puts their annotations on the union and calls their `generate`. A new `:serialization-kotlinx` module implements the extension: it validates members, adds `@Serializable(with = …)` and writes `<Union>Serializer`. Its tests live in `:serialization-kotlinx-tests` so the extension never activates in `:processor-tests`; the compilation harness moves to `:processor-tests`' test fixtures so both modules share it.

**Tech Stack:** Kotlin 2.4.20, KSP 2.3.12 (KSP2), KotlinPoet 2.4.0, kotlinx-serialization-json 1.11.0 (tests; 1.6.3 minimum to verify), kotlin-serialization-compiler-plugin-embeddable 2.4.20, kotlin-compile-testing 0.14.0, Gradle `java-test-fixtures`.

**Spec:** `docs/superpowers/specs/2026-09-23-serialization-kotlinx-design.md` — read it before starting any task.

## Global Constraints

- Versions stay as pinned in `gradle/libs.versions.toml` (Kotlin 2.4.20, KSP 2.3.12, KotlinPoet 2.4.0, JVM target 17). New catalog entries: `kotlinxSerialization = "1.11.0"`, the serialization compiler plugin at the Kotlin version, the serialization Gradle plugin at the Kotlin version.
- The processor must use no KSP API newer than KSP 2.3.0. Keep the single-argument `validate()` with its `@Suppress("DEPRECATION")`.
- `:serialization-kotlinx` must not depend on kotlinx.serialization; it only emits code that uses it.
- Every precondition fails through `logger.error`. Never guess, never silently fall back.
- Code is emitted with KotlinPoet, never string concatenation of source files.
- Generated serializer code may only use public kotlinx.serialization API, opting in to `ExperimentalSerializationApi` where needed (never `InternalSerializationApi`).
- Commit author is `Shahriar Zaman <nayeem.zxc@gmail.com>` (already configured repo-locally). **Do not add any `Co-Authored-By` trailer.**
- Run Gradle from the repo root: `/home/portonics/development/project/unionKt`.

## File Structure

| File | Responsibility |
| --- | --- |
| `processor-api/build.gradle.kts` (create) | Published module; `api` deps on KSP API and KotlinPoet. |
| `processor-api/src/main/kotlin/com/github/fcat97/unionkt/api/UnionExtension.kt` (create) | `UnionExtension`, `UnionInfo`, `UnionMemberInfo`, `ExtensionEnvironment`. |
| `processor/src/main/kotlin/.../processor/ExtensionSupport.kt` (create) | `loadExtensions()`, `UnionModel.toInfo(...)`. |
| `processor/src/main/kotlin/.../processor/UnionProcessor.kt` (modify) | Runs extensions around `writer.write`. |
| `processor/src/main/kotlin/.../processor/UnionModel.kt`, `MemberResolver.kt`, `UnionWriter.kt` (modify) | Carry member declarations; accept extra union annotations. |
| `processor-tests/src/testFixtures/kotlin/.../processor/UnionCompilation.kt` (move from `src/test`) | Shared compilation harness, now public. |
| `processor-tests/src/test/kotlin/.../processor/TestExtension.kt`, `UnionExtensionTest.kt`, `src/test/resources/META-INF/services/...` (create) | Extension API tests. |
| `serialization-kotlinx/build.gradle.kts` (create) | Published extension module. |
| `serialization-kotlinx/src/main/kotlin/com/github/fcat97/unionkt/serialization/kotlinx/KotlinxSerializationExtension.kt` (create) | Validation, annotation, orchestration. |
| `serialization-kotlinx/src/main/kotlin/com/github/fcat97/unionkt/serialization/kotlinx/SerializerWriter.kt` (create) | Emits `<Union>Serializer`. |
| `serialization-kotlinx/src/main/resources/META-INF/services/com.github.fcat97.unionkt.api.UnionExtension` (create) | Registration. |
| `serialization-kotlinx-tests/...` (create) | Behaviour and compile-check tests. |

`...` in paths abbreviates `com/github/fcat97/unionkt`.

---

### Task 1: Extension API and processor integration

**Files:**
- Modify: `settings.gradle.kts`
- Create: `processor-api/build.gradle.kts`
- Create: `processor-api/src/main/kotlin/com/github/fcat97/unionkt/api/UnionExtension.kt`
- Modify: `processor/build.gradle.kts`
- Modify: `processor/src/main/kotlin/com/github/fcat97/unionkt/processor/UnionModel.kt`
- Modify: `processor/src/main/kotlin/com/github/fcat97/unionkt/processor/MemberResolver.kt`
- Modify: `processor/src/main/kotlin/com/github/fcat97/unionkt/processor/UnionWriter.kt`
- Create: `processor/src/main/kotlin/com/github/fcat97/unionkt/processor/ExtensionSupport.kt`
- Modify: `processor/src/main/kotlin/com/github/fcat97/unionkt/processor/UnionProcessor.kt`
- Modify: `processor-tests/build.gradle.kts`
- Move: `processor-tests/src/test/kotlin/com/github/fcat97/unionkt/processor/UnionCompilation.kt` → `processor-tests/src/testFixtures/kotlin/com/github/fcat97/unionkt/processor/UnionCompilation.kt`
- Create: `processor-tests/src/test/kotlin/com/github/fcat97/unionkt/processor/TestExtension.kt`
- Create: `processor-tests/src/test/resources/META-INF/services/com.github.fcat97.unionkt.api.UnionExtension`
- Test: `processor-tests/src/test/kotlin/com/github/fcat97/unionkt/processor/UnionExtensionTest.kt`

**Interfaces:**
- Produces (public, `com.github.fcat97.unionkt.api`): `UnionExtension { fun unionAnnotations(union: UnionInfo, env: ExtensionEnvironment): List<AnnotationSpec>; fun generate(union: UnionInfo, env: ExtensionEnvironment) }`, `UnionInfo(markerName, marker, unionType, visibility, typeParameters, members, sources)`, `UnionMemberInfo(simpleName, typeName, caseClass, typeParameterName, declaration)`, `ExtensionEnvironment(codeGenerator, logger, resolver)`.
- Produces (test fixtures, public): `compileWithUnionProcessor(vararg sources: SourceFile, classpath: List<File> = emptyList(), compilerPlugins: List<CompilerPluginRegistrar> = emptyList(), classpathFilter: ((File) -> Boolean)? = null): UnionCompilationResult` and `UnionCompilationResult` with `exitCode`, `messages`, `outputDirectory`, `assertSucceeded()`, `assertFailedWith(vararg)`, `assertWarns`, `assertDoesNotWarn`, `generated(fileName)`, `generatedFileNames()`, `call(className, functionName)`.

- [ ] **Step 1: Register the new module and create `:processor-api`**

In `settings.gradle.kts`, after `include(":processor")`, add `include(":processor-api")`.

Create `processor-api/build.gradle.kts`:

```kotlin
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    `maven-publish`
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    withSourcesJar()
}

dependencies {
    // Both appear in the public signatures, so extensions get them transitively.
    api(libs.ksp.api)
    api(libs.kotlinpoet)
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])

            // Never hardcoded: both come from the root build script, which reads
            // them from JitPack's environment when running there.
            groupId = project.group.toString()
            artifactId = project.name
            version = project.version.toString()

            pom {
                name.set("unionKt processor API")
                description.set("Extension API for the unionKt KSP processor.")
                url.set("https://github.com/fcat97/unionKt")
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }
                scm {
                    url.set("https://github.com/fcat97/unionKt")
                    connection.set("scm:git:https://github.com/fcat97/unionKt.git")
                }
            }
        }
    }
}
```

Create `processor-api/src/main/kotlin/com/github/fcat97/unionkt/api/UnionExtension.kt`:

```kotlin
package com.github.fcat97.unionkt.api

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeVariableName

/**
 * A plugin for the unionKt processor.
 *
 * Register an implementation in
 * `META-INF/services/com.github.fcat97.unionkt.api.UnionExtension` and put its artifact on
 * the `ksp` configuration next to the processor. The processor calls it for every union:
 * first [unionAnnotations], whose results are added to the generated union interface, then,
 * after the union file is written, [generate].
 *
 * Report problems through [ExtensionEnvironment.logger]. An exception thrown from either
 * function is reported as a compile error naming the extension.
 */
public interface UnionExtension {
    /** Extra annotations for the generated union interface. Also the place to validate. */
    public fun unionAnnotations(union: UnionInfo, env: ExtensionEnvironment): List<AnnotationSpec> = emptyList()

    /** Extra files for this union. */
    public fun generate(union: UnionInfo, env: ExtensionEnvironment) {}
}

/** A fully resolved union: generics, flattening and visibility are already applied. */
public class UnionInfo(
    /** The Spec marker's simple name, e.g. `ResultSpec`. */
    public val markerName: String,
    /** The Spec marker, for attaching diagnostics. */
    public val marker: KSClassDeclaration,
    /** The generated union, e.g. `com.example.Result`. */
    public val unionType: ClassName,
    /** `PUBLIC` or `INTERNAL`. */
    public val visibility: KModifier,
    /** As declared on the union: always `out`, bounds kept. Empty for a non-generic union. */
    public val typeParameters: List<TypeVariableName>,
    /** Every case, in case order. Flattened members are ordinary members here. */
    public val members: List<UnionMemberInfo>,
    /** The files the union was generated from; pass them to `Dependencies`. */
    public val sources: List<KSFile>,
)

/** One case of a union. */
public class UnionMemberInfo(
    /** The case suffix: `Int` for `OnInt`, `L` for `OnL`. */
    public val simpleName: String,
    /** The type the case stores. */
    public val typeName: TypeName,
    /** The case class, e.g. `com.example.Result.OnInt`. */
    public val caseClass: ClassName,
    /** The union type parameter this case stores, or null for a concrete case. */
    public val typeParameterName: String?,
    /** The member's class declaration, or null for a type-parameter case. */
    public val declaration: KSClassDeclaration?,
)

/** What an extension may use while handling one union. */
public class ExtensionEnvironment(
    public val codeGenerator: CodeGenerator,
    public val logger: KSPLogger,
    /** The current round's resolver. */
    public val resolver: Resolver,
)
```

- [ ] **Step 2: Move the harness into test fixtures and make it public**

Replace `processor-tests/build.gradle.kts` with:

```kotlin
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// NOT published. Compiles Kotlin sources in-memory with the processor attached so
// the error paths can be asserted on, which a normal build cannot do: a failing
// processor fails the build. The compilation harness lives in test fixtures so that
// :serialization-kotlinx-tests can reuse it.
plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-test-fixtures`
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
        // kotlin-compile-testing drives the compiler through its plugin API, which is
        // experimental by definition. Opting in once here beats annotating every call.
        optIn.add("org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi")
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    testFixturesApi(project(":annotations"))
    testFixturesApi(project(":processor"))
    testFixturesApi(libs.kotlin.compile.testing.ksp)
    testFixturesImplementation(kotlin("test"))

    testImplementation(project(":processor-api"))
    testImplementation(libs.kotlinpoet.ksp)
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
    }
}
```

Move the harness:

```bash
mkdir -p processor-tests/src/testFixtures/kotlin/com/github/fcat97/unionkt/processor
git mv processor-tests/src/test/kotlin/com/github/fcat97/unionkt/processor/UnionCompilation.kt processor-tests/src/testFixtures/kotlin/com/github/fcat97/unionkt/processor/UnionCompilation.kt
```

Then replace the moved file's contents with:

```kotlin
package com.github.fcat97.unionkt.processor

import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.configureKsp
import com.tschuchort.compiletesting.kspSourcesDir
import com.tschuchort.compiletesting.useKsp2
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Runs the Kotlin compiler in-process with [UnionProcessorProvider] attached.
 *
 * The `:sample` module can only prove the *happy* path: a processor that calls
 * `logger.error` fails the build, so failure cases cannot live in a normal source
 * set. Compiling from strings here is what makes the error paths assertable.
 *
 * @param classpath extra dependencies, e.g. a "library" compiled by an earlier call.
 * @param compilerPlugins compiler plugins to run, e.g. kotlinx.serialization's.
 * @param classpathFilter when set, the compiled sources see only the host classpath
 *   entries it accepts (instead of the whole host classpath). The processor itself still
 *   runs from the host classpath either way.
 */
public fun compileWithUnionProcessor(
    vararg sources: SourceFile,
    classpath: List<File> = emptyList(),
    compilerPlugins: List<CompilerPluginRegistrar> = emptyList(),
    classpathFilter: ((File) -> Boolean)? = null,
): UnionCompilationResult {
    val compilation = KotlinCompilation().apply {
        this.sources = sources.toList()
        // Puts the :annotations module (and kotlin-stdlib) on the compiled sources'
        // classpath, so `import com.github.fcat97.unionkt.Union` resolves.
        inheritClassPath = classpathFilter == null
        classpaths = classpath + (classpathFilter?.let { accept -> hostClasspath().filter(accept) } ?: emptyList())
        compilerPluginRegistrars = compilerPlugins
        useKsp2()
        configureKsp {
            symbolProcessorProviders += UnionProcessorProvider()
        }
    }

    val result = compilation.compile()
    return UnionCompilationResult(result, compilation.kspSourcesDir)
}

private fun hostClasspath(): List<File> =
    System.getProperty("java.class.path").split(File.pathSeparator).filter { it.isNotBlank() }.map(::File)

public class UnionCompilationResult(
    private val result: JvmCompilationResult,
    private val kspSourcesDir: File,
) {
    public val exitCode: KotlinCompilation.ExitCode get() = result.exitCode
    public val messages: String get() = result.messages

    /** The compiled classes, to pass as `classpath` to a later compilation. */
    public val outputDirectory: File get() = result.outputDirectory

    private val generatedFiles: List<File>
        get() = kspSourcesDir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    /** Asserts the compilation succeeded, printing the compiler output when it did not. */
    public fun assertSucceeded(): UnionCompilationResult = apply {
        assertEquals(
            KotlinCompilation.ExitCode.OK,
            exitCode,
            "expected compilation to succeed, but it failed with:\n$messages",
        )
    }

    /** Asserts the compilation failed *and* that the failure is the expected diagnostic. */
    public fun assertFailedWith(vararg expectedFragments: String): UnionCompilationResult = apply {
        assertEquals(
            KotlinCompilation.ExitCode.COMPILATION_ERROR,
            exitCode,
            "expected compilation to fail, but it succeeded. Output:\n$messages",
        )
        expectedFragments.forEach { fragment ->
            assertTrue(
                messages.contains(fragment),
                "expected an error containing:\n  $fragment\nbut the output was:\n$messages",
            )
        }
    }

    public fun assertWarns(fragment: String): UnionCompilationResult = apply {
        assertTrue(
            messages.contains(fragment),
            "expected a warning containing:\n  $fragment\nbut the output was:\n$messages",
        )
    }

    public fun assertDoesNotWarn(fragment: String): UnionCompilationResult = apply {
        assertTrue(
            !messages.contains(fragment),
            "expected no warning containing:\n  $fragment\nbut the output was:\n$messages",
        )
    }

    /** The text of a generated file, e.g. `generated("Result.kt")`. */
    public fun generated(fileName: String): String {
        val file = generatedFiles.firstOrNull { it.name == fileName }
        return requireNotNull(file) {
            "no generated file named '$fileName'; generated: ${generatedFiles.map { it.name }}"
        }.readText()
    }

    public fun generatedFileNames(): List<String> = generatedFiles.map { it.name }.sorted()

    /**
     * Runs a top-level, no-argument function from the compiled sources and returns its
     * result, e.g. `call("test.UseKt", "verify")`. A failing `check` inside it fails the test.
     */
    public fun call(className: String, functionName: String): Any? =
        result.classLoader.loadClass(className).getMethod(functionName).invoke(null)
}
```

- [ ] **Step 3: Confirm the move kept the suite green**

Run: `./gradlew :processor-tests:test --console=plain`
Expected: `BUILD SUCCESSFUL`, all 51 tests pass.

- [ ] **Step 4: Write the failing extension tests**

Create `processor-tests/src/test/resources/META-INF/services/com.github.fcat97.unionkt.api.UnionExtension` containing exactly:

```
com.github.fcat97.unionkt.processor.TestExtension
```

Create `processor-tests/src/test/kotlin/com/github/fcat97/unionkt/processor/TestExtension.kt`:

```kotlin
package com.github.fcat97.unionkt.processor

import com.github.fcat97.unionkt.api.ExtensionEnvironment
import com.github.fcat97.unionkt.api.UnionExtension
import com.github.fcat97.unionkt.api.UnionInfo
import com.google.devtools.ksp.processing.Dependencies
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.ksp.writeTo

/**
 * A test-only extension, discovered through this module's test resources.
 *
 * It only reacts to markers whose names start with `Ext`, so every other test in the
 * module runs exactly as if no extension were installed.
 */
class TestExtension : UnionExtension {

    override fun unionAnnotations(union: UnionInfo, env: ExtensionEnvironment): List<AnnotationSpec> =
        when (union.markerName) {
            "ExtAnnotatedSpec" -> listOf(
                AnnotationSpec.builder(Deprecated::class).addMember("%S", "from extension").build(),
            )
            "ExtThrowsSpec" -> error("boom")
            else -> emptyList()
        }

    override fun generate(union: UnionInfo, env: ExtensionEnvironment) {
        val dependencies = Dependencies(aggregating = false, *union.sources.toTypedArray())
        when (union.markerName) {
            "ExtGeneratedSpec" -> FileSpec.builder(union.unionType.packageName, "ExtGeneratedExtra")
                .addFunction(
                    FunSpec.builder("describeFromExtension")
                        .receiver(union.unionType)
                        .returns(String::class)
                        .addStatement("return %S", "from extension")
                        .build(),
                )
                .build()
                .writeTo(env.codeGenerator, dependencies)

            "ExtInfoSpec" -> FileSpec.builder(union.unionType.packageName, "ExtInfoReport")
                .addFileComment("%L", describe(union))
                .build()
                .writeTo(env.codeGenerator, dependencies)
        }
    }

    private fun describe(union: UnionInfo): String = buildString {
        appendLine("union=${union.unionType} visibility=${union.visibility} marker=${union.marker.simpleName.asString()}")
        appendLine("typeParameters=${union.typeParameters.map { "${it.variance?.name?.lowercase()} ${it.name}" }}")
        union.members.forEach { member ->
            appendLine(
                "member ${member.simpleName} case=${member.caseClass} type=${member.typeName} " +
                    "typeParameter=${member.typeParameterName} " +
                    "declaration=${member.declaration?.qualifiedName?.asString()}",
            )
        }
    }
}
```

Create `processor-tests/src/test/kotlin/com/github/fcat97/unionkt/processor/UnionExtensionTest.kt`:

```kotlin
package com.github.fcat97.unionkt.processor

import com.tschuchort.compiletesting.SourceFile
import kotlin.test.Test
import kotlin.test.assertContains

/** The processor's extension API, exercised through [TestExtension]. */
class UnionExtensionTest {

    @Test
    fun `an extension's annotations are added to the union`() {
        val generated = compileWithUnionProcessor(
            SourceFile.kotlin(
                "ExtAnnotated.kt",
                """
                package test

                import com.github.fcat97.unionkt.Union

                @Union(Int::class)
                interface ExtAnnotatedSpec
                """.trimIndent(),
            ),
        ).assertSucceeded().generated("ExtAnnotated.kt")

        assertContains(generated, "@Deprecated(\"from extension\")")
    }

    @Test
    fun `an extension can generate its own files`() {
        compileWithUnionProcessor(
            SourceFile.kotlin(
                "ExtGenerated.kt",
                """
                package test

                import com.github.fcat97.unionkt.Union

                @Union(Int::class)
                interface ExtGeneratedSpec

                fun verify() {
                    check(ExtGenerated(1).describeFromExtension() == "from extension")
                }
                """.trimIndent(),
            ),
        ).assertSucceeded().call("test.ExtGeneratedKt", "verify")
    }

    @Test
    fun `an extension sees the fully resolved union`() {
        val report = compileWithUnionProcessor(
            SourceFile.kotlin(
                "ExtInfo.kt",
                """
                package test

                import com.github.fcat97.unionkt.Union

                data class Circle(val radius: Int)

                @Union(Circle::class)
                internal interface ShapeSpec

                @Union(Int::class, ShapeSpec::class)
                internal interface ExtInfoSpec<T>
                """.trimIndent(),
            ),
        ).assertSucceeded().generated("ExtInfoReport.kt")

        assertContains(report, "union=test.ExtInfo visibility=INTERNAL marker=ExtInfoSpec")
        assertContains(report, "typeParameters=[out T]")
        assertContains(report, "member Int case=test.ExtInfo.OnInt type=kotlin.Int typeParameter=null declaration=kotlin.Int")
        assertContains(report, "member Circle case=test.ExtInfo.OnCircle type=test.Circle typeParameter=null declaration=test.Circle")
        assertContains(report, "member T case=test.ExtInfo.OnT type=T typeParameter=T declaration=null")
    }

    @Test
    fun `an extension that throws becomes a compile error`() {
        compileWithUnionProcessor(
            SourceFile.kotlin(
                "ExtThrows.kt",
                """
                package test

                import com.github.fcat97.unionkt.Union

                @Union(Int::class)
                interface ExtThrowsSpec
                """.trimIndent(),
            ),
        ).assertFailedWith(
            "unionKt extension 'com.github.fcat97.unionkt.processor.TestExtension' failed on 'ExtThrowsSpec': boom",
        )
    }
}
```

- [ ] **Step 5: Run the new tests and confirm they fail**

Run: `./gradlew :processor-tests:test --tests '*UnionExtensionTest*' --console=plain`
Expected: FAIL — the processor does not load extensions yet (no annotation, `Unresolved reference 'describeFromExtension'`, no `ExtInfoReport.kt`, no extension error).

- [ ] **Step 6: Carry member declarations and expose `declaredVariable`**

In `processor/build.gradle.kts`, add `implementation(project(":processor-api"))` to `dependencies`, directly after `implementation(project(":annotations"))`.

In `UnionModel.kt`:

1. Add the import `com.google.devtools.ksp.symbol.KSClassDeclaration` and `com.squareup.kotlinpoet.TypeVariableName`.
2. Add as the last property of `UnionMember`:

```kotlin
    /** The member's class declaration; null for a type-parameter case. Exposed to extensions. */
    val declaration: KSClassDeclaration? = null,
```

3. Append to the file:

```kotlin
/** As declared on the union and on its case class: always `out`, bounds kept. */
internal fun UnionTypeParameter.declaredVariable(): TypeVariableName =
    TypeVariableName(name, bounds, KModifier.OUT)
```

In `UnionWriter.kt`, delete the private `UnionTypeParameter.declaredVariable()` function (with its KDoc line) — the internal one in `UnionModel.kt` replaces it.

In `MemberResolver.kt`, in `concreteMember`, add `declaration = declaration as? KSClassDeclaration,` after `via = via,` inside the `UnionMember(...)` call.

- [ ] **Step 7: Let the writer take extra union annotations**

In `UnionWriter.kt`:

1. Change the `write` signature to

```kotlin
    fun write(model: UnionModel, sources: List<KSFile>, annotations: List<AnnotationSpec> = emptyList()) {
```

2. In `write`, change `.addType(unionInterface(model))` to `.addType(unionInterface(model, annotations))`.
3. Change `private fun unionInterface(model: UnionModel): TypeSpec {` to `private fun unionInterface(model: UnionModel, annotations: List<AnnotationSpec>): TypeSpec {`, and add `.addAnnotations(annotations)` directly after `.addModifiers(model.visibility, KModifier.SEALED)`.

- [ ] **Step 8: Create `ExtensionSupport.kt`**

```kotlin
package com.github.fcat97.unionkt.processor

import com.github.fcat97.unionkt.api.UnionExtension
import com.github.fcat97.unionkt.api.UnionInfo
import com.github.fcat97.unionkt.api.UnionMemberInfo
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFile
import java.util.ServiceLoader

/**
 * Every extension registered in `META-INF/services`. KSP loads the whole `ksp`
 * configuration into one classloader, so an extension shipped as a separate `ksp(...)`
 * artifact is visible through the loader that loaded the API itself.
 */
internal fun loadExtensions(): List<UnionExtension> =
    ServiceLoader.load(UnionExtension::class.java, UnionExtension::class.java.classLoader).toList()

/** The read-only view of a resolved union handed to extensions. */
internal fun UnionModel.toInfo(marker: KSClassDeclaration, sources: List<KSFile>): UnionInfo = UnionInfo(
    markerName = markerName,
    marker = marker,
    unionType = unionType,
    visibility = visibility,
    typeParameters = typeParameters.map { it.declaredVariable() },
    members = members.map { member ->
        UnionMemberInfo(
            simpleName = member.simpleName,
            typeName = member.typeName,
            caseClass = unionType.nestedClass(CASE_PREFIX + member.simpleName),
            typeParameterName = member.typeParameter?.name,
            declaration = member.declaration,
        )
    },
    sources = sources,
)
```

- [ ] **Step 9: Run extensions from `UnionProcessor`**

In `UnionProcessor.kt`:

1. Add imports:

```kotlin
import com.github.fcat97.unionkt.api.ExtensionEnvironment
import com.github.fcat97.unionkt.api.UnionExtension
```

2. Replace the class header and fields

```kotlin
internal class UnionProcessor(
    codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
) : SymbolProcessor {

    private val memberResolver = MemberResolver(logger)
    private val writer = UnionWriter(codeGenerator)
```

with

```kotlin
internal class UnionProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
    private val extensions: List<UnionExtension> = loadExtensions(),
) : SymbolProcessor {

    private val memberResolver = MemberResolver(logger)
    private val writer = UnionWriter(codeGenerator)
```

3. In `process`, change `generateUnion(symbol)` to `generateUnion(symbol, resolver)`.
4. Change `private fun generateUnion(marker: KSClassDeclaration) {` to `private fun generateUnion(marker: KSClassDeclaration, resolver: Resolver) {`.
5. Replace the final `writer.write(...)` call in `generateUnion` with:

```kotlin
        val model = UnionModel(
            markerName = markerName,
            unionType = ClassName(packageName, unionName),
            visibility = visibility,
            members = resolution.members,
            typeParameters = typeParameters,
            flattened = resolution.flattened,
        )
        val info = model.toInfo(marker, resolution.sources)
        val environment = ExtensionEnvironment(codeGenerator, logger, resolver)

        val annotations = extensions.flatMap { extension ->
            runExtension(extension, marker, markerName) { extension.unionAnnotations(info, environment) }.orEmpty()
        }
        writer.write(model, resolution.sources, annotations)
        extensions.forEach { extension ->
            runExtension(extension, marker, markerName) { extension.generate(info, environment) }
        }
    }

    /** Runs one extension call, turning an exception into a compile error that names it. */
    private inline fun <T> runExtension(
        extension: UnionExtension,
        marker: KSClassDeclaration,
        markerName: String,
        call: () -> T,
    ): T? = try {
        call()
    } catch (e: Exception) {
        logger.error(
            "unionKt extension '${extension::class.qualifiedName}' failed on '$markerName': ${e.message}",
            marker,
        )
        null
```

(The `}` that closed `generateUnion` now closes `runExtension`; check the braces balance.)

- [ ] **Step 10: Run the extension tests**

Run: `./gradlew :processor-tests:test --tests '*UnionExtensionTest*' --console=plain`
Expected: PASS, all 4 tests.

- [ ] **Step 11: Run the full build**

Run: `./gradlew build --console=plain`
Expected: `BUILD SUCCESSFUL`; 55 tests in `:processor-tests`.

- [ ] **Step 12: Commit**

```bash
git add settings.gradle.kts processor-api processor processor-tests
git commit -m "Add an extension API to the processor

A new :processor-api module defines UnionExtension and a read-only
UnionInfo. The processor loads extensions with ServiceLoader, adds
their annotations to each union and calls their generate; an extension
that throws becomes a compile error naming it. The test harness moves
to :processor-tests' test fixtures so other test modules can share it."
```

---

### Task 2: The kotlinx.serialization extension (serializer generation)

**Files:**
- Modify: `settings.gradle.kts`
- Modify: `gradle/libs.versions.toml`
- Create: `serialization-kotlinx/build.gradle.kts`
- Create: `serialization-kotlinx/src/main/resources/META-INF/services/com.github.fcat97.unionkt.api.UnionExtension`
- Create: `serialization-kotlinx/src/main/kotlin/com/github/fcat97/unionkt/serialization/kotlinx/KotlinxSerializationExtension.kt`
- Create: `serialization-kotlinx/src/main/kotlin/com/github/fcat97/unionkt/serialization/kotlinx/SerializerWriter.kt`
- Create: `serialization-kotlinx-tests/build.gradle.kts`
- Create: `serialization-kotlinx-tests/src/test/kotlin/com/github/fcat97/unionkt/serialization/kotlinx/SerializationCompilation.kt`
- Test: `serialization-kotlinx-tests/src/test/kotlin/com/github/fcat97/unionkt/serialization/kotlinx/KotlinxSerializationTest.kt`
- Modify: `docs/superpowers/specs/2026-09-23-serialization-kotlinx-design.md` (§3.1 descriptor line)

**Interfaces:**
- Consumes (Task 1): the `com.github.fcat97.unionkt.api` types; test fixtures `compileWithUnionProcessor(..., compilerPlugins = ...)`, `UnionCompilationResult.call`.
- Produces: `public class KotlinxSerializationExtension : UnionExtension` (no-arg constructor); `internal fun serializerClassName(union: UnionInfo): ClassName`; `internal class SerializerWriter(codeGenerator: CodeGenerator) { fun write(union: UnionInfo) }`; test helper `internal fun compileWithSerialization(vararg sources: SourceFile, classpathFilter: ((File) -> Boolean)? = null): UnionCompilationResult`.

**Spec deviation (record it in the spec in Step 8):** the descriptor wraps `ContextualSerializer(Any::class).descriptor` instead of `JsonElement.serializer().descriptor`. `JsonElement`'s descriptor has kind `SEALED`, so a union nested as a case of another union (`@Union(String::class, Shape::class)`) would only ever be tried for JSON objects by the outer union's shape filter. Kind `CONTEXTUAL` is always tried, which is correct: a union can be any shape.

- [ ] **Step 1: Add the catalog entries and modules**

In `gradle/libs.versions.toml`:
- under `[versions]` add `kotlinxSerialization = "1.11.0"`;
- under `[libraries]` add
  ```toml
  kotlinx-serialization-json = { module = "org.jetbrains.kotlinx:kotlinx-serialization-json", version.ref = "kotlinxSerialization" }
  kotlin-serialization-plugin-embeddable = { module = "org.jetbrains.kotlin:kotlin-serialization-compiler-plugin-embeddable", version.ref = "kotlin" }
  ```
- under `[plugins]` add `kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }`.

In `settings.gradle.kts`, after `include(":processor-tests")`, add:

```kotlin
include(":serialization-kotlinx")
include(":serialization-kotlinx-tests")
```

Create `serialization-kotlinx/build.gradle.kts`:

```kotlin
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    `maven-publish`
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    withSourcesJar()
}

dependencies {
    // No kotlinx.serialization dependency on purpose: this module only *emits* code that
    // uses it, and the consuming project brings the runtime.
    implementation(project(":processor-api"))
    implementation(libs.kotlinpoet.ksp)
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])

            // Never hardcoded: both come from the root build script, which reads
            // them from JitPack's environment when running there.
            groupId = project.group.toString()
            artifactId = project.name
            version = project.version.toString()

            pom {
                name.set("unionKt kotlinx.serialization")
                description.set("unionKt extension that generates untagged kotlinx.serialization JSON serializers for unions.")
                url.set("https://github.com/fcat97/unionKt")
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }
                scm {
                    url.set("https://github.com/fcat97/unionKt")
                    connection.set("scm:git:https://github.com/fcat97/unionKt.git")
                }
            }
        }
    }
}
```

Create `serialization-kotlinx/src/main/resources/META-INF/services/com.github.fcat97.unionkt.api.UnionExtension` containing exactly:

```
com.github.fcat97.unionkt.serialization.kotlinx.KotlinxSerializationExtension
```

Create `serialization-kotlinx-tests/build.gradle.kts`:

```kotlin
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// NOT published. Tests for :serialization-kotlinx, kept out of :processor-tests because
// the extension is discovered from the classpath: there it would activate in every test.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Overridable so the suite can be run against the oldest supported runtime:
//   ./gradlew :serialization-kotlinx-tests:test -PkotlinxSerializationVersion=1.6.3
val kotlinxSerializationVersion: String = providers.gradleProperty("kotlinxSerializationVersion")
    .getOrElse(libs.versions.kotlinxSerialization.get())

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
        optIn.add("org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi")
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    testImplementation(testFixtures(project(":processor-tests")))
    testImplementation(project(":serialization-kotlinx"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$kotlinxSerializationVersion")
    testImplementation(libs.kotlin.serialization.plugin.embeddable)
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    inputs.property("kotlinxSerializationVersion", kotlinxSerializationVersion)
    testLogging {
        events("passed", "failed", "skipped")
    }
}
```

- [ ] **Step 2: Write the test helper and the failing behaviour tests**

Create `serialization-kotlinx-tests/src/test/kotlin/com/github/fcat97/unionkt/serialization/kotlinx/SerializationCompilation.kt`:

```kotlin
package com.github.fcat97.unionkt.serialization.kotlinx

import com.github.fcat97.unionkt.processor.UnionCompilationResult
import com.github.fcat97.unionkt.processor.compileWithUnionProcessor
import com.tschuchort.compiletesting.SourceFile
import org.jetbrains.kotlinx.serialization.compiler.extensions.SerializationComponentRegistrar
import java.io.File

/** Compiles with the unionKt processor, this module's extension, and the serialization compiler plugin. */
internal fun compileWithSerialization(
    vararg sources: SourceFile,
    classpathFilter: ((File) -> Boolean)? = null,
): UnionCompilationResult = compileWithUnionProcessor(
    *sources,
    compilerPlugins = listOf(SerializationComponentRegistrar()),
    classpathFilter = classpathFilter,
)
```

Create `serialization-kotlinx-tests/src/test/kotlin/com/github/fcat97/unionkt/serialization/kotlinx/KotlinxSerializationTest.kt`:

```kotlin
package com.github.fcat97.unionkt.serialization.kotlinx

import com.tschuchort.compiletesting.SourceFile
import kotlin.test.Test
import kotlin.test.assertContains

/**
 * Runtime behaviour of the generated serializers. Every test compiles real code with the
 * serialization compiler plugin and runs its `verify()`.
 *
 * The embedded sources avoid `$` templates: they sit inside this file's raw strings.
 */
class KotlinxSerializationTest {

    private fun run(fileName: String, source: String) {
        compileWithSerialization(SourceFile.kotlin(fileName, source))
            .assertSucceeded()
            .call("test." + fileName.removeSuffix(".kt") + "Kt", "verify")
    }

    @Test
    fun `the union is annotated and a serializer object is generated`() {
        val result = compileWithSerialization(
            SourceFile.kotlin(
                "Plain.kt",
                """
                package test

                import com.github.fcat97.unionkt.Union

                @Union(Int::class, String::class)
                interface PlainSpec
                """.trimIndent(),
            ),
        ).assertSucceeded()

        assertContains(result.generated("Plain.kt"), "@Serializable(with = PlainSerializer::class)")
        assertContains(result.generated("PlainSerializer.kt"), "public object PlainSerializer : KSerializer<Plain>")
    }

    @Test
    fun `primitives, objects and arrays round-trip untagged`() = run(
        "Values.kt",
        """
        package test

        import com.github.fcat97.unionkt.Union
        import kotlinx.serialization.Serializable
        import kotlinx.serialization.encodeToString
        import kotlinx.serialization.json.Json

        @Serializable
        data class User(val name: String)

        @Union(Int::class, String::class, Boolean::class, Double::class, User::class, IntArray::class)
        interface ValueSpec

        fun roundTrip(value: Value, expected: String) {
            val text = Json.encodeToString<Value>(value)
            check(text == expected) { "encoded " + value + " as " + text }
            val back = Json.decodeFromString<Value>(text)
            check(back == value) { "decoded " + text + " as " + back }
        }

        fun verify() {
            roundTrip(Value(5), "5")
            roundTrip(Value("hi"), "\"hi\"")
            roundTrip(Value(true), "true")
            roundTrip(Value(5.5), "5.5")
            roundTrip(Value(User("Ada")), "{\"name\":\"Ada\"}")
            check(Json.encodeToString<Value>(Value(intArrayOf(1, 2))) == "[1,2]")
            val array = Json.decodeFromString<Value>("[1,2]")
            check(array is Value.OnIntArray && array.value.toList() == listOf(1, 2)) { array.toString() }
        }
        """.trimIndent(),
    )

    @Test
    fun `enums and value classes are matched by the kind they serialize as`() = run(
        "Measure.kt",
        """
        package test

        import com.github.fcat97.unionkt.Union
        import kotlinx.serialization.Serializable
        import kotlinx.serialization.encodeToString
        import kotlinx.serialization.json.Json

        enum class Color { RED, GREEN }

        @Serializable
        @JvmInline
        value class Meters(val value: Double)

        @Union(Color::class, Meters::class)
        interface MeasureSpec

        fun verify() {
            check(Json.decodeFromString<Measure>("\"RED\"") == Measure(Color.RED))
            check(Json.decodeFromString<Measure>("2.5") == Measure(Meters(2.5)))
            check(Json.encodeToString<Measure>(Measure(Meters(2.5))) == "2.5")
        }
        """.trimIndent(),
    )

    @Test
    fun `a generic union works as a property and at top level`() = run(
        "Generic.kt",
        """
        package test

        import com.github.fcat97.unionkt.Union
        import kotlinx.serialization.Serializable
        import kotlinx.serialization.encodeToString
        import kotlinx.serialization.json.Json

        @Union
        interface EitherSpec<L, R>

        @Serializable
        data class Holder(val e: Either<String, Int>)

        fun verify() {
            check(Json.encodeToString(Holder(Either.onR(5))) == "{\"e\":5}")
            check(Json.decodeFromString<Holder>("{\"e\":\"x\"}") == Holder(Either.onL("x")))
            check(Json.decodeFromString<Either<String, Int>>("5") == Either.onR(5))
        }
        """.trimIndent(),
    )

    @Test
    fun `flattened and nested unions decode`() = run(
        "Shapes.kt",
        """
        package test

        import com.github.fcat97.unionkt.Union
        import kotlinx.serialization.Serializable
        import kotlinx.serialization.encodeToString
        import kotlinx.serialization.json.Json

        @Serializable
        data class Circle(val radius: Int)

        @Serializable
        data class Square(val side: Int)

        @Union(Circle::class, Square::class)
        interface ShapeSpec

        @Union(Int::class, ShapeSpec::class)
        interface ItemSpec

        @Union(String::class, Shape::class)
        interface NestSpec

        fun verify() {
            check(Json.decodeFromString<Item>("{\"side\":2}") == Item(Square(2)))
            check(Json.decodeFromString<Nest>("{\"radius\":1}") == Nest(Shape(Circle(1))))
            check(Json.encodeToString<Nest>(Nest(Shape(Circle(1)))) == "{\"radius\":1}")
            check(Json.decodeFromString<Nest>("\"text\"") == Nest("text"))
        }
        """.trimIndent(),
    )

    @Test
    fun `a quoted number stays a string`() = run(
        "Text.kt",
        """
        package test

        import com.github.fcat97.unionkt.Union
        import kotlinx.serialization.json.Json

        @Union(Int::class, String::class)
        interface TextSpec

        fun verify() {
            check(Json.decodeFromString<Text>("\"5\"") == Text("5"))
            check(Json.decodeFromString<Text>("5") == Text(5))
        }
        """.trimIndent(),
    )

    @Test
    fun `declaration order decides between object cases`() = run(
        "Order.kt",
        """
        package test

        import com.github.fcat97.unionkt.Union
        import kotlinx.serialization.Serializable
        import kotlinx.serialization.json.Json

        @Serializable
        data class User(val name: String)

        @Serializable
        data class Admin(val name: String, val level: Int)

        @Union(User::class, Admin::class)
        interface UserFirstSpec

        @Union(Admin::class, User::class)
        interface AdminFirstSpec

        fun verify() {
            val lenient = Json { ignoreUnknownKeys = true }
            val bob = "{\"name\":\"Bob\",\"level\":3}"
            check(lenient.decodeFromString<UserFirst>(bob) == UserFirst(User("Bob")))
            check(lenient.decodeFromString<AdminFirst>(bob) == AdminFirst(Admin("Bob", 3)))
            check(lenient.decodeFromString<AdminFirst>("{\"name\":\"Ada\"}") == AdminFirst(User("Ada")))
        }
        """.trimIndent(),
    )

    @Test
    fun `input that matches no case fails with a clear message`() = run(
        "Pick.kt",
        """
        package test

        import com.github.fcat97.unionkt.Union
        import kotlinx.serialization.SerializationException
        import kotlinx.serialization.Serializable
        import kotlinx.serialization.json.Json

        @Serializable
        data class User(val name: String)

        @Union(Int::class, User::class)
        interface PickSpec

        @Serializable
        data class Required(val p: Pick)

        @Serializable
        data class Optional(val p: Pick? = null)

        fun messageOf(block: () -> Unit): String {
            val error = runCatching(block).exceptionOrNull()
            check(error is SerializationException) { "expected a SerializationException, got " + error }
            return error.message.orEmpty()
        }

        fun verify() {
            val noShape = messageOf { Json.decodeFromString<Pick>("\"text\"") }
            check("Cannot decode a string as Pick: no case accepts a string" in noShape) { noShape }

            val tried = messageOf { Json.decodeFromString<Pick>("{\"x\":1}") }
            check("Cannot decode an object as Pick: tried OnUser:" in tried) { tried }

            val nullValue = messageOf { Json.decodeFromString<Required>("{\"p\":null}") }
            check("Cannot decode null as Pick" in nullValue) { nullValue }

            check(Json.decodeFromString<Optional>("{\"p\":null}") == Optional(null))
        }
        """.trimIndent(),
    )

    @Test
    fun `a non-JSON decoder is rejected`() = run(
        "NotJson.kt",
        """
        @file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

        package test

        import com.github.fcat97.unionkt.Union
        import kotlinx.serialization.SerializationException
        import kotlinx.serialization.descriptors.SerialDescriptor
        import kotlinx.serialization.encoding.AbstractDecoder
        import kotlinx.serialization.encoding.CompositeDecoder
        import kotlinx.serialization.modules.EmptySerializersModule
        import kotlinx.serialization.modules.SerializersModule

        @Union(Int::class)
        interface OnlySpec

        class NotJson : AbstractDecoder() {
            override val serializersModule: SerializersModule = EmptySerializersModule()
            override fun decodeElementIndex(descriptor: SerialDescriptor): Int = CompositeDecoder.DECODE_DONE
        }

        fun verify() {
            val error = runCatching { OnlySerializer.deserialize(NotJson()) }.exceptionOrNull()
            check(error is SerializationException) { "got " + error }
            check("OnlySerializer supports JSON only" in error.message.orEmpty()) { error.message.orEmpty() }
        }
        """.trimIndent(),
    )
}
```

- [ ] **Step 3: Run the tests and confirm they fail**

Run: `./gradlew :serialization-kotlinx-tests:test --console=plain`
Expected: FAIL at runtime. `:serialization-kotlinx` has no sources yet, so no extension is registered: the unions get no `@Serializable(with = …)`, `PlainSerializer.kt` is missing, and decoding fails with `Serializer for class 'Value' is not found`.

- [ ] **Step 4: Create `KotlinxSerializationExtension.kt`**

```kotlin
package com.github.fcat97.unionkt.serialization.kotlinx

import com.github.fcat97.unionkt.api.ExtensionEnvironment
import com.github.fcat97.unionkt.api.UnionExtension
import com.github.fcat97.unionkt.api.UnionInfo
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName

/**
 * Attaches an untagged kotlinx.serialization JSON serializer to every union.
 *
 * Discovered through `META-INF/services` when this artifact is on the `ksp` configuration.
 */
public class KotlinxSerializationExtension : UnionExtension {

    /** Unions that failed validation: they get neither the annotation nor a serializer. */
    private val rejected = mutableSetOf<ClassName>()

    override fun unionAnnotations(union: UnionInfo, env: ExtensionEnvironment): List<AnnotationSpec> {
        if (!validate(union, env)) {
            rejected += union.unionType
            return emptyList()
        }
        return listOf(
            AnnotationSpec.builder(SERIALIZABLE)
                .addMember("with = %T::class", serializerClassName(union))
                .build(),
        )
    }

    override fun generate(union: UnionInfo, env: ExtensionEnvironment) {
        if (union.unionType in rejected) return
        SerializerWriter(env.codeGenerator).write(union)
    }

    /** Reports every problem that would make the generated serializer wrong; true when there were none. */
    @Suppress("UNUSED_PARAMETER")
    private fun validate(union: UnionInfo, env: ExtensionEnvironment): Boolean = true

    internal companion object {
        val SERIALIZABLE = ClassName("kotlinx.serialization", "Serializable")
    }
}

/** `com.example.Result` → `com.example.ResultSerializer`. */
internal fun serializerClassName(union: UnionInfo): ClassName =
    ClassName(union.unionType.packageName, union.unionType.simpleName + "Serializer")
```

(`validate` is filled in by Task 3.)

- [ ] **Step 5: Create `SerializerWriter.kt`**

```kotlin
package com.github.fcat97.unionkt.serialization.kotlinx

import com.github.fcat97.unionkt.api.UnionInfo
import com.github.fcat97.unionkt.api.UnionMemberInfo
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.squareup.kotlinpoet.ANY
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.BOOLEAN
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.LIST
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.STRING
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.TypeVariableName
import com.squareup.kotlinpoet.ksp.writeTo

/**
 * Emits `<Union>Serializer`: an `object` for a plain union, a class taking one
 * `KSerializer` per type parameter for a generic one.
 *
 * Serializing writes the case's inner value only. Deserializing reads a `JsonElement`,
 * keeps the cases whose descriptor kind can produce that JSON shape, and tries them in
 * declaration order; the first that decodes wins.
 *
 * Every multi-token expression in the emitted code sits inside parentheses: KotlinPoet may
 * wrap long lines at any space, and Kotlin ignores line breaks only inside brackets.
 */
internal class SerializerWriter(private val codeGenerator: CodeGenerator) {

    fun write(union: UnionInfo) {
        val serializerType = serializerClassName(union)
        val typeVariables = union.typeParameters.map { TypeVariableName(it.name, it.bounds) }
        val unionType: TypeName =
            if (typeVariables.isEmpty()) union.unionType
            else union.unionType.parameterizedBy(typeVariables.map { TypeVariableName(it.name) })

        val type = if (typeVariables.isEmpty()) {
            TypeSpec.objectBuilder(serializerType)
        } else {
            TypeSpec.classBuilder(serializerType)
                .addTypeVariables(typeVariables)
                .primaryConstructor(
                    FunSpec.constructorBuilder().apply {
                        typeVariables.forEach { addParameter(parameterSerializerName(it.name), serializerOf(TypeVariableName(it.name))) }
                    }.build(),
                )
                .apply {
                    typeVariables.forEach {
                        val name = parameterSerializerName(it.name)
                        addProperty(
                            PropertySpec.builder(name, serializerOf(TypeVariableName(it.name)), KModifier.PRIVATE)
                                .initializer(name)
                                .build(),
                        )
                    }
                }
        }

        type.addModifiers(union.visibility)
            .addAnnotation(AnnotationSpec.builder(OPT_IN).addMember("%T::class", EXPERIMENTAL_API).build())
            .addSuperinterface(serializerOf(unionType))
            .addKdoc(
                "Untagged JSON serializer for [%T], generated by unionKt from [%L].\n\n" +
                    "Writes a case's inner value. Reads by keeping the cases whose kind matches the " +
                    "JSON shape, then trying them in declaration order.",
                union.unionType,
                union.markerName,
            )

        union.members.filter { it.typeParameterName == null }.forEach { member ->
            type.addProperty(
                PropertySpec.builder(serializerReference(member), serializerOf(member.typeName), KModifier.PRIVATE)
                    .initializer("%M<%T>()", SERIALIZER_FUNCTION, member.typeName)
                    .build(),
            )
        }

        type.addProperty(
            PropertySpec.builder("descriptor", SERIAL_DESCRIPTOR, KModifier.OVERRIDE)
                // CONTEXTUAL kind: a union can be any JSON shape, so an outer union's shape
                // filter must always try it.
                .initializer(
                    "%M(%S, %T(%T::class).descriptor)",
                    SERIAL_DESCRIPTOR_FUNCTION,
                    union.unionType.canonicalName,
                    CONTEXTUAL_SERIALIZER,
                    ANY,
                )
                .build(),
        )
        type.addFunction(serialize(union, unionType))
        type.addFunction(deserialize(union, unionType, serializerType))
        type.addFunction(accepts())
        type.addFunction(noMatch(union))

        FileSpec.builder(serializerType)
            .addFileComment("Generated by unionKt serialization-kotlinx from @Union on %L. Do not edit.", union.markerName)
            .addType(type.build())
            .build()
            .writeTo(codeGenerator, Dependencies(aggregating = false, *union.sources.toTypedArray()))
    }

    private fun serialize(union: UnionInfo, unionType: TypeName): FunSpec {
        val body = CodeBlock.builder().beginControlFlow("when (value)")
        union.members.forEach { member ->
            body.addStatement(
                "is %T -> encoder.encodeSerializableValue(%N, value.value)",
                member.caseClass,
                serializerReference(member),
            )
        }
        body.endControlFlow()
        return FunSpec.builder("serialize")
            .addModifiers(KModifier.OVERRIDE)
            .addParameter("encoder", ENCODER)
            .addParameter("value", unionType)
            .addCode(body.build())
            .build()
    }

    private fun deserialize(union: UnionInfo, unionType: TypeName, serializerType: ClassName): FunSpec {
        val body = CodeBlock.builder()
            .addStatement(
                "val input = (decoder as? %T ?: throw %T(%S + decoder::class.qualifiedName + %S))",
                JSON_DECODER,
                SERIALIZATION_EXCEPTION,
                "${serializerType.simpleName} supports JSON only; got ",
                ".",
            )
            .addStatement("val element = input.decodeJsonElement()")
            .addStatement("val failures = mutableListOf<%T>()", STRING)

        union.members.forEach { member ->
            val reference = serializerReference(member)
            body.beginControlFlow("if (accepts(%N.descriptor, element))", reference)
                .beginControlFlow("try")
                .addStatement("return %T(input.json.decodeFromJsonElement(%N, element))", member.caseClass, reference)
                .nextControlFlow("catch (e: %T)", ILLEGAL_ARGUMENT_EXCEPTION)
                .addStatement("failures += (%S + e.message)", "${member.caseClass.simpleName}: ")
                .endControlFlow()
                .endControlFlow()
        }
        body.addStatement("throw noMatch(element, failures)")

        return FunSpec.builder("deserialize")
            .addModifiers(KModifier.OVERRIDE)
            .addParameter("decoder", DECODER)
            .returns(unionType)
            .addCode(body.build())
            .build()
    }

    /** Whether a serializer with this descriptor can produce this JSON value. */
    private fun accepts(): FunSpec = FunSpec.builder("accepts")
        .addModifiers(KModifier.PRIVATE)
        .addParameter("descriptor", SERIAL_DESCRIPTOR)
        .addParameter("element", JSON_ELEMENT)
        .returns(BOOLEAN)
        .addStatement("val kind = (if (descriptor.isInline) descriptor.getElementDescriptor(0) else descriptor).kind")
        .addStatement("if (element is %T) return false", JSON_NULL)
        .addStatement("if (kind == %T.CONTEXTUAL) return true", SERIAL_KIND)
        .beginControlFlow("return when (element)")
        .addStatement(
            "is %T -> (kind == %T.CLASS || kind == %T.OBJECT || kind == %T.MAP || kind is %T)",
            JSON_OBJECT, STRUCTURE_KIND, STRUCTURE_KIND, STRUCTURE_KIND, POLYMORPHIC_KIND,
        )
        .addStatement("is %T -> (kind == %T.LIST)", JSON_ARRAY, STRUCTURE_KIND)
        .beginControlFlow("is %T -> when", JSON_PRIMITIVE)
        .addStatement(
            "element.isString -> (kind == %T.STRING || kind == %T.CHAR || kind == %T.ENUM)",
            PRIMITIVE_KIND, PRIMITIVE_KIND, SERIAL_KIND,
        )
        .addStatement("(element.%M != null) -> (kind == %T.BOOLEAN)", BOOLEAN_OR_NULL, PRIMITIVE_KIND)
        .addStatement(
            "else -> (kind == %T.INT || kind == %T.LONG || kind == %T.SHORT || kind == %T.BYTE || " +
                "kind == %T.FLOAT || kind == %T.DOUBLE)",
            PRIMITIVE_KIND, PRIMITIVE_KIND, PRIMITIVE_KIND, PRIMITIVE_KIND, PRIMITIVE_KIND, PRIMITIVE_KIND,
        )
        .endControlFlow()
        .endControlFlow()
        .build()

    private fun noMatch(union: UnionInfo): FunSpec = FunSpec.builder("noMatch")
        .addModifiers(KModifier.PRIVATE)
        .addParameter("element", JSON_ELEMENT)
        .addParameter("failures", LIST.parameterizedBy(STRING))
        .returns(SERIALIZATION_EXCEPTION)
        .beginControlFlow("val shape = when")
        .addStatement("(element is %T) -> %S", JSON_NULL, "null")
        .addStatement("(element is %T) -> %S", JSON_OBJECT, "an object")
        .addStatement("(element is %T) -> %S", JSON_ARRAY, "an array")
        .addStatement("(element is %T && element.isString) -> %S", JSON_PRIMITIVE, "a string")
        .addStatement("(element is %T && element.%M != null) -> %S", JSON_PRIMITIVE, BOOLEAN_OR_NULL, "a boolean")
        .addStatement("else -> %S", "a number")
        .endControlFlow()
        .addStatement(
            "val detail = (if (failures.isEmpty()) %S + shape else %S + failures.joinToString(%S))",
            "no case accepts ",
            "tried ",
            "; ",
        )
        .addStatement(
            "return %T(%S + shape + %S + detail + %S)",
            SERIALIZATION_EXCEPTION,
            "Cannot decode ",
            " as ${union.unionType.simpleName}: ",
            ".",
        )
        .build()

    private fun serializerOf(type: TypeName): TypeName = K_SERIALIZER.parameterizedBy(type)

    /** The property holding a case's serializer: a constructor property for a type-parameter case. */
    private fun serializerReference(member: UnionMemberInfo): String =
        member.typeParameterName?.let(::parameterSerializerName) ?: "on${member.simpleName}Serializer"

    /** `L` → `lSerializer`. */
    private fun parameterSerializerName(typeParameter: String): String =
        typeParameter.replaceFirstChar(Char::lowercaseChar) + "Serializer"

    private companion object {
        const val SERIALIZATION = "kotlinx.serialization"
        const val DESCRIPTORS = "kotlinx.serialization.descriptors"
        const val JSON = "kotlinx.serialization.json"

        val K_SERIALIZER = ClassName(SERIALIZATION, "KSerializer")
        val SERIALIZATION_EXCEPTION = ClassName(SERIALIZATION, "SerializationException")
        val EXPERIMENTAL_API = ClassName(SERIALIZATION, "ExperimentalSerializationApi")
        val CONTEXTUAL_SERIALIZER = ClassName(SERIALIZATION, "ContextualSerializer")
        val SERIALIZER_FUNCTION = MemberName(SERIALIZATION, "serializer")
        val OPT_IN = ClassName("kotlin", "OptIn")
        val ILLEGAL_ARGUMENT_EXCEPTION = ClassName("kotlin", "IllegalArgumentException")

        val SERIAL_DESCRIPTOR = ClassName(DESCRIPTORS, "SerialDescriptor")
        val SERIAL_DESCRIPTOR_FUNCTION = MemberName(DESCRIPTORS, "SerialDescriptor")
        val SERIAL_KIND = ClassName(DESCRIPTORS, "SerialKind")
        val PRIMITIVE_KIND = ClassName(DESCRIPTORS, "PrimitiveKind")
        val STRUCTURE_KIND = ClassName(DESCRIPTORS, "StructureKind")
        val POLYMORPHIC_KIND = ClassName(DESCRIPTORS, "PolymorphicKind")

        val ENCODER = ClassName("kotlinx.serialization.encoding", "Encoder")
        val DECODER = ClassName("kotlinx.serialization.encoding", "Decoder")

        val JSON_DECODER = ClassName(JSON, "JsonDecoder")
        val JSON_ELEMENT = ClassName(JSON, "JsonElement")
        val JSON_NULL = ClassName(JSON, "JsonNull")
        val JSON_OBJECT = ClassName(JSON, "JsonObject")
        val JSON_ARRAY = ClassName(JSON, "JsonArray")
        val JSON_PRIMITIVE = ClassName(JSON, "JsonPrimitive")
        val BOOLEAN_OR_NULL = MemberName(JSON, "booleanOrNull")
    }
}
```

- [ ] **Step 6: Run the behaviour tests**

Run: `./gradlew :serialization-kotlinx-tests:test --console=plain`
Expected: PASS, all 9 tests. If a test fails on a *generated-code compile error*, read `result.messages` in the failure; the usual cause is KotlinPoet wrapping a line at a space outside brackets — wrap that expression in parentheses in `SerializerWriter`.

- [ ] **Step 7: Run the full build**

Run: `./gradlew build --console=plain`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 8: Record the descriptor deviation in the spec**

In `docs/superpowers/specs/2026-09-23-serialization-kotlinx-design.md` §3.1, replace

```markdown
Descriptor: `SerialDescriptor("<qualified union name>", JsonElement.serializer().descriptor)`,
with `@OptIn(ExperimentalSerializationApi::class)`.
```

with

```markdown
Descriptor: `SerialDescriptor("<qualified union name>", ContextualSerializer(Any::class).descriptor)`,
with `@OptIn(ExperimentalSerializationApi::class)`. Its kind is `CONTEXTUAL`, so an outer union's
shape filter (§3.3) always tries a union nested as a case; `JsonElement`'s descriptor (kind
`SEALED`, used in the spike) would restrict a nested union to JSON objects.
```

- [ ] **Step 9: Commit**

```bash
git add settings.gradle.kts gradle/libs.versions.toml serialization-kotlinx serialization-kotlinx-tests docs/superpowers/specs/2026-09-23-serialization-kotlinx-design.md
git commit -m "Add the serialization-kotlinx extension

Adds @Serializable(with = <Union>Serializer::class) to every union and
generates an untagged JSON serializer: it writes a case's inner value,
and reads by keeping the cases whose descriptor kind matches the JSON
shape, then trying them in declaration order. Tested in a separate
module with the real serialization compiler plugin."
```

---

### Task 3: Compile-time checks in the kotlinx extension

**Files:**
- Modify: `serialization-kotlinx/src/main/kotlin/com/github/fcat97/unionkt/serialization/kotlinx/KotlinxSerializationExtension.kt`
- Test: `serialization-kotlinx-tests/src/test/kotlin/com/github/fcat97/unionkt/serialization/kotlinx/KotlinxSerializationChecksTest.kt`

**Interfaces:**
- Consumes (Task 2): `KotlinxSerializationExtension`, `compileWithSerialization(..., classpathFilter)`.
- Produces: the three errors of spec §4.

- [ ] **Step 1: Write the failing tests**

Create `KotlinxSerializationChecksTest.kt`:

```kotlin
package com.github.fcat97.unionkt.serialization.kotlinx

import com.tschuchort.compiletesting.SourceFile
import kotlin.test.Test
import kotlin.test.assertEquals

/** The extension's compile-time checks (spec §4). */
class KotlinxSerializationChecksTest {

    @Test
    fun `a star-projected member is rejected`() {
        compileWithSerialization(
            SourceFile.kotlin(
                "BadList.kt",
                """
                package test

                import com.github.fcat97.unionkt.Union

                @Union(List::class, Int::class)
                interface BadListSpec
                """.trimIndent(),
            ),
        ).assertFailedWith(
            "@Union on 'BadListSpec' cannot serialize member 'List<*>': a class literal cannot say its element type.",
        )
    }

    @Test
    fun `a class without @Serializable is rejected`() {
        compileWithSerialization(
            SourceFile.kotlin(
                "Money.kt",
                """
                package test

                import com.github.fcat97.unionkt.Union

                class Money(val cents: Long)

                @Union(Money::class)
                interface MoneySpec
                """.trimIndent(),
            ),
        ).assertFailedWith("@Union on 'MoneySpec' cannot serialize member 'test.Money': it is not @Serializable.")
    }

    @Test
    fun `a java type is rejected`() {
        compileWithSerialization(
            SourceFile.kotlin(
                "Ids.kt",
                """
                package test

                import com.github.fcat97.unionkt.Union

                @Union(java.util.UUID::class, String::class)
                interface IdSpec
                """.trimIndent(),
            ),
        ).assertFailedWith("@Union on 'IdSpec' cannot serialize member 'java.util.UUID': it is not @Serializable.")
    }

    @Test
    fun `kotlin types, enums and @Serializable classes pass`() {
        compileWithSerialization(
            SourceFile.kotlin(
                "Fine.kt",
                """
                package test

                import com.github.fcat97.unionkt.Union
                import kotlinx.serialization.Serializable

                enum class Color { RED }

                @Serializable
                data class User(val name: String)

                @Union(Int::class, IntArray::class, Color::class, User::class)
                interface FineSpec
                """.trimIndent(),
            ),
        ).assertSucceeded()
    }

    @Test
    fun `a missing kotlinx runtime is reported once`() {
        val result = compileWithSerialization(
            SourceFile.kotlin(
                "Plain.kt",
                """
                package test

                import com.github.fcat97.unionkt.Union

                @Union(Int::class)
                interface PlainSpec

                @Union(String::class)
                interface OtherSpec
                """.trimIndent(),
            ),
            classpathFilter = { "kotlinx-serialization" !in it.name },
        ).assertFailedWith(
            "serialization-kotlinx is installed, but kotlinx-serialization-core is not on the classpath. " +
                "Add the kotlinx-serialization-json dependency.",
        )

        assertEquals(1, Regex("serialization-kotlinx is installed").findAll(result.messages).count(), result.messages)
    }
}
```

- [ ] **Step 2: Run the tests and confirm they fail**

Run: `./gradlew :serialization-kotlinx-tests:test --tests '*KotlinxSerializationChecksTest*' --console=plain`
Expected: FAIL — the star-projected, `Money`, `UUID` and missing-runtime cases compile further or fail with other messages; `kotlin types, enums and @Serializable classes pass` already passes.

- [ ] **Step 3: Implement the checks**

In `KotlinxSerializationExtension.kt`:

1. Add imports:

```kotlin
import com.github.fcat97.unionkt.api.UnionMemberInfo
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.squareup.kotlinpoet.ParameterizedTypeName
import com.squareup.kotlinpoet.WildcardTypeName
```

2. Add a field after `rejected`:

```kotlin
    /** Whether kotlinx.serialization is on the compile classpath; checked once per compilation. */
    private var runtimeAvailable: Boolean? = null
```

3. Replace the placeholder `validate` (and its `@Suppress` line) with:

```kotlin
    /** Reports every problem that would make the generated serializer wrong; true when there were none. */
    private fun validate(union: UnionInfo, env: ExtensionEnvironment): Boolean {
        if (!runtimeAvailable(env)) return false

        var valid = true
        union.members.filter { it.typeParameterName == null }.forEach { member ->
            val problem = problemWith(member) ?: return@forEach
            env.logger.error("@Union on '${union.markerName}' cannot serialize member $problem", union.marker)
            valid = false
        }
        return valid
    }

    private fun runtimeAvailable(env: ExtensionEnvironment): Boolean = runtimeAvailable ?: run {
        val name = env.resolver.getKSNameFromString(SERIALIZABLE.canonicalName)
        val available = env.resolver.getClassDeclarationByName(name) != null
        if (!available) {
            env.logger.error(
                "serialization-kotlinx is installed, but kotlinx-serialization-core is not on the " +
                    "classpath. Add the kotlinx-serialization-json dependency.",
            )
        }
        available.also { runtimeAvailable = it }
    }

    /** Why a concrete member cannot be serialized, or null when it can. */
    private fun problemWith(member: UnionMemberInfo): String? {
        val type = member.typeName
        if (type is ParameterizedTypeName && type.typeArguments.any { it is WildcardTypeName }) {
            val stars = type.typeArguments.joinToString { "*" }
            return "'${member.simpleName}<$stars>': a class literal cannot say its element type. " +
                "Wrap it in a @Serializable class."
        }
        val declaration = member.declaration ?: return null
        if (isSerializable(declaration)) return null
        return "'${declaration.qualifiedName?.asString() ?: member.simpleName}': it is not @Serializable. " +
            "Annotate it, or wrap it in a @Serializable class."
    }

    /**
     * `@Serializable` (with or without `with =`), an enum, or a `kotlin.*` type. Serializers
     * registered only at runtime (contextual, `@file:UseSerializers`) are invisible here.
     */
    private fun isSerializable(declaration: KSClassDeclaration): Boolean {
        val packageName = declaration.packageName.asString()
        return declaration.classKind == ClassKind.ENUM_CLASS ||
            packageName == "kotlin" ||
            packageName.startsWith("kotlin.") ||
            declaration.annotations.any {
                it.annotationType.resolve().declaration.qualifiedName?.asString() == SERIALIZABLE.canonicalName
            }
    }
```

- [ ] **Step 4: Run the checks tests**

Run: `./gradlew :serialization-kotlinx-tests:test --tests '*KotlinxSerializationChecksTest*' --console=plain`
Expected: PASS, all 5 tests.

- [ ] **Step 5: Run the full build**

Run: `./gradlew build --console=plain`
Expected: `BUILD SUCCESSFUL`; `:serialization-kotlinx-tests` runs 14 tests.

- [ ] **Step 6: Commit**

```bash
git add serialization-kotlinx serialization-kotlinx-tests
git commit -m "Check union members are serializable at compile time

serializer<T>() on a non-serializable type compiles and fails only at
runtime, so the extension now rejects star-projected members and member
classes that are neither @Serializable, enums nor kotlin.* types, and
reports a missing kotlinx runtime once."
```

---

### Task 4: Minimum runtime check, sample and documentation

**Files:**
- Modify: `build.gradle.kts` (root)
- Modify: `sample/build.gradle.kts`
- Modify: `sample/src/main/kotlin/com/github/fcat97/unionkt/sample/User.kt`
- Modify: `sample/src/main/kotlin/com/github/fcat97/unionkt/sample/DrawableSample.kt`
- Modify: `sample/src/main/kotlin/com/github/fcat97/unionkt/sample/Main.kt`
- Modify: `README.md`

**Interfaces:**
- Consumes: everything above. Produces documentation only.

- [ ] **Step 1: Verify the minimum kotlinx runtime**

Run: `./gradlew :serialization-kotlinx-tests:test -PkotlinxSerializationVersion=1.6.3 --console=plain`
Expected: `BUILD SUCCESSFUL`, all 14 tests pass.
**If it fails:** stop. Do not change the minimum on your own — report the failing tests and the missing API to the user (spec §5 requires raising the minimum and updating the spec, which is their call).

- [ ] **Step 2: Wire the extension into the sample**

In the root `build.gradle.kts`, add `alias(libs.plugins.kotlin.serialization) apply false` inside `plugins { … }`.

In `sample/build.gradle.kts`:
- add `alias(libs.plugins.kotlin.serialization)` inside `plugins { … }`;
- replace the `dependencies` block with:

```kotlin
dependencies {
    implementation(project(":annotations"))
    implementation(libs.kotlinx.serialization.json)
    ksp(project(":processor"))
    // Opt-in serialization: every union in this module gets an untagged JSON serializer.
    ksp(project(":serialization-kotlinx"))
}
```

Replace `User.kt` with:

```kotlin
package com.github.fcat97.unionkt.sample

import kotlinx.serialization.Serializable

@Serializable
public data class User(val id: Long, val name: String)
```

In `DrawableSample.kt`, add `import kotlinx.serialization.Serializable` after the `Union` import, and annotate both data classes:

```kotlin
@Serializable
public data class Circle(val radius: Double)

@Serializable
public data class Square(val side: Double)
```

In `Main.kt`, add `import kotlinx.serialization.encodeToString` and `import kotlinx.serialization.json.Json` below the package line, and append to the end of `main`:

```kotlin

    println(Json.encodeToString<Result>(Result(User(id = 3, name = "Linus"))))
    println(Json.decodeFromString<Result>("\"from json\""))
    println(Json.decodeFromString<Either<String, Int>>("42"))
    println(Json.decodeFromString<Drawable>("{\"side\":3.0}"))
```

- [ ] **Step 3: Build and run the sample**

Run: `./gradlew :sample:build --console=plain && java -cp "sample/build/classes/kotlin/main:$(find ~/.gradle/caches/modules-2 -name 'kotlin-stdlib-2.4.20.jar' | head -1):$(find ~/.gradle/caches/modules-2 -name 'kotlinx-serialization-core-jvm-1.11.0.jar' | head -1):$(find ~/.gradle/caches/modules-2 -name 'kotlinx-serialization-json-jvm-1.11.0.jar' | head -1)" com.github.fcat97.unionkt.sample.MainKt`
Expected: the 11 lines from before, followed by

```
{"id":3,"name":"Linus"}
OnString(value=from json)
OnR(value=42)
OnSquare(value=Square(side=3.0))
```

- [ ] **Step 4: Update the README**

1. In `## Install`, change every `0.2.0` to `0.3.0` (the two dependency lines and the "Replace `0.2.0` with the git tag you want" sentence).

2. Insert this section immediately **before** `## Exhaustiveness guarantee`:

````markdown
## Serialization (kotlinx)

Opt in by adding the extension next to the processor, plus the usual kotlinx.serialization setup:

```kotlin
plugins {
    kotlin("plugin.serialization") version "2.4.20"
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")   // 1.6.3 or newer
    ksp("com.github.fcat97.unionKt:processor:0.3.0")
    ksp("com.github.fcat97.unionKt:serialization-kotlinx:0.3.0")
}
```

Every union in the module then gets `@Serializable(with = ResultSerializer::class)` and a
generated `ResultSerializer`, so it works at top level and as a property of your own
`@Serializable` classes, generic unions included:

```kotlin
Json.encodeToString<Result>(Result(User("Ada")))        // {"name":"Ada"}
Json.decodeFromString<Result>("5")                       // Result.OnInt(5)
Json.decodeFromString<Either<String, Int>>("\"oops\"")   // Either.OnL("oops")
```

**Untagged.** A case is written as its bare inner value — no wrapper, no `"type"` field. JSON only:
other formats cannot pick a case without a tag.

**How reading picks a case:**

1. Keep the cases whose serializer can produce that JSON shape: strings, chars and enums for a
   string; numbers for a number; booleans; classes, objects, maps and sealed types for an object;
   lists and arrays for an array. Nested unions and contextual serializers are always kept. This is
   why `"5"` decodes to `OnString`, not `OnInt`, in an `Int | String` union.
2. Try those cases in declaration order; the first that decodes wins.
3. Nothing decodes: `SerializationException` naming the union and every case tried.

Order matters when two object types overlap. With `ignoreUnknownKeys = true`, `{"name":"Bob","level":3}`
decodes as whichever of `User(name)` and `Admin(name, level)` is listed first — so **list the more
specific type first**.

**Compile-time checks.** A member that is not `@Serializable`, an enum or a `kotlin.*` type is a
compile error, as is a star-projected member such as `List::class`. A type made serializable only
by a runtime contextual serializer or `@file:UseSerializers` is rejected too, because the processor
cannot see those — wrap it in a `@Serializable` class.

## Writing an extension

Serialization is built on a public extension API, so other integrations (Moshi, Gson, …) can be
separate artifacts. Depend on `com.github.fcat97.unionKt:processor-api`, implement
`UnionExtension`, and register it in
`META-INF/services/com.github.fcat97.unionkt.api.UnionExtension`:

```kotlin
class MyExtension : UnionExtension {
    // Added to the generated union interface. Also the place to validate.
    override fun unionAnnotations(union: UnionInfo, env: ExtensionEnvironment): List<AnnotationSpec> = emptyList()

    // Extra files, written after the union.
    override fun generate(union: UnionInfo, env: ExtensionEnvironment) {}
}
```

`UnionInfo` is the fully resolved union: its `ClassName`, visibility, type parameters and every
case (flattened members included) with its type, case class and declaration. Users enable an
extension by adding its artifact to `ksp(...)`. An extension that throws is reported as a compile
error naming it.

---
````

3. In the `## Errors` table, append:

```markdown
| An extension throws | `unionKt extension 'com.example.MyExtension' failed on 'ResultSpec': <message>` |
| *(serialization-kotlinx)* Star-projected member | `@Union on 'BadListSpec' cannot serialize member 'List<*>': a class literal cannot say its element type. …` |
| *(serialization-kotlinx)* Member not serializable | `@Union on 'MoneySpec' cannot serialize member 'test.Money': it is not @Serializable. …` |
| *(serialization-kotlinx)* kotlinx runtime missing | `serialization-kotlinx is installed, but kotlinx-serialization-core is not on the classpath. …` |
```

4. In the `## Modules` table, add rows after `:processor`:

```markdown
| `:processor-api`  | yes    | The public extension API: `UnionExtension`, `UnionInfo`. |
| `:serialization-kotlinx` | yes | Extension generating untagged kotlinx.serialization JSON serializers. |
| `:serialization-kotlinx-tests` | **no** | Its test suite, run with the real serialization compiler plugin. |
```

5. In `## Tests` → `Covered:`, append:

```markdown
- **Extensions** — annotations reach the union, generated files compile, `UnionInfo` carries
  flattened members and type parameters, and a throwing extension is a named compile error.
- **Serialization** (`:serialization-kotlinx-tests`) — untagged round trips for primitives,
  objects, arrays, enums, value classes, generic, flattened and nested unions; `"5"` stays a
  string; declaration order between object cases; no-match, `null` and non-JSON errors; the
  compile-time checks. Run against the oldest supported runtime with
  `./gradlew :serialization-kotlinx-tests:test -PkotlinxSerializationVersion=1.6.3`.
```

- [ ] **Step 5: Run the full build**

Run: `./gradlew build --console=plain`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add build.gradle.kts sample README.md
git commit -m "Document serialization and the extension API

The sample opts in to serialization-kotlinx and round-trips unions;
the README covers setup, how reading picks a case, the compile-time
checks and writing an extension. Verified against kotlinx 1.6.3."
```
