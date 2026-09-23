# Union Ergonomics Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give every generated union constructor functions, `fold` and case accessors, and add generic unions and flattening (including across modules).

**Architecture:** `UnionProcessor` is split into three stages that talk through a plain data model (`UnionModel`): the processor validates the marker, `MemberResolver` works out the cases (type parameters, flattening, merging, clash detection), and `UnionWriter` emits the file with KotlinPoet. Every new feature is a change to one stage plus tests in `:processor-tests` that compile — and where useful, *run* — consuming code.

**Tech Stack:** Kotlin 2.4.20, KSP 2.3.12 (KSP2), KotlinPoet 2.4.0 + kotlinpoet-ksp, kotlin-compile-testing (ZacSweers fork) 0.14.0, JUnit 5 via `kotlin("test")`.

**Spec:** `docs/superpowers/specs/2026-09-23-union-ergonomics-design.md` — read it before starting any task.

## Global Constraints

- Versions stay as pinned in `gradle/libs.versions.toml`: Kotlin 2.4.20, KSP 2.3.12, KotlinPoet 2.4.0, JVM target 17. No new dependencies.
- The processor must use no KSP API newer than KSP 2.3.0 (the README promises "any 2.3.x"). Keep the single-argument `validate()` call and its `@Suppress("DEPRECATION")`.
- Every precondition fails through `logger.error` and skips the marker. Never guess, never silently fall back.
- Code is emitted with KotlinPoet, never string concatenation.
- Existing generated API stays source-compatible: sealed interface, `On<T>` data-class cases, companion `on<T>` factories.
- Commit author is `Shahriar Zaman <nayeem.zxc@gmail.com>` (already configured repo-locally). **Do not add any `Co-Authored-By` trailer.**
- Run Gradle from the repo root: `/home/portonics/development/project/unionKt`.

## File Structure

`processor/src/main/kotlin/com/github/fcat97/unionkt/processor/`:

| File | Responsibility |
| --- | --- |
| `UnionProcessor.kt` (modify) | KSP entry: validates the marker (kind, name, package, uniqueness, type parameters, visibility), then calls resolver and writer. |
| `Naming.kt` (create) | Shared constants and pure naming functions: `unionNameOf`, `accessorStem`, `freeTypeVariableName`. |
| `UnionModel.kt` (create) | Plain data passed from resolver/processor to writer: `UnionModel`, `UnionMember`, `UnionTypeParameter`, `FlattenedUnion`. |
| `MemberResolver.kt` (create) | Reads `@Union` arguments, adds type-parameter cases, flattens nested markers, merges duplicates, reports clashes and cycles. |
| `UnionWriter.kt` (create) | Emits the union file: interface, cases, companion, constructor functions, `fold`, accessors, conversions. |

`processor-tests/src/test/kotlin/com/github/fcat97/unionkt/processor/`:

| File | Responsibility |
| --- | --- |
| `UnionCompilation.kt` (modify) | Harness: add `call(...)` to run compiled code, `outputDirectory`, and a `classpath` parameter. |
| `UnionGenericsTest.kt` (create) | Task 2. |
| `UnionHelpersTest.kt` (create) | Task 3. |
| `UnionFlatteningTest.kt` (create) | Task 4. |
| `UnionCrossModuleTest.kt` (create) | Task 5. |

`:annotations` `Union.kt`, `:sample` and `README.md` change in Tasks 5–6.

---

### Task 1: Split `UnionProcessor` into resolver, writer and model (no behaviour change)

**Files:**
- Create: `processor/src/main/kotlin/com/github/fcat97/unionkt/processor/Naming.kt`
- Create: `processor/src/main/kotlin/com/github/fcat97/unionkt/processor/UnionModel.kt`
- Create: `processor/src/main/kotlin/com/github/fcat97/unionkt/processor/MemberResolver.kt`
- Create: `processor/src/main/kotlin/com/github/fcat97/unionkt/processor/UnionWriter.kt`
- Modify (full rewrite): `processor/src/main/kotlin/com/github/fcat97/unionkt/processor/UnionProcessor.kt`
- Test: existing `processor-tests` suite (unchanged)

**Interfaces:**
- Produces:
  - `internal fun unionNameOf(markerName: String): String?` and constants `UNION_ANNOTATION_NAME`, `UNION_ANNOTATION_SIMPLE_NAME`, `TYPES_ARGUMENT`, `SPEC_SUFFIX`, `CASE_PREFIX`, `FACTORY_PREFIX`, `VALUE_NAME` in `Naming.kt`.
  - `internal data class UnionMember(simpleName: String, qualifiedName: String, typeName: TypeName)`.
  - `internal data class UnionModel(markerName: String, unionType: ClassName, visibility: KModifier, members: List<UnionMember>)`.
  - `internal class MemberResolver(logger: KSPLogger) { fun resolve(marker: KSClassDeclaration, markerName: String): List<UnionMember>? }`.
  - `internal fun KSClassDeclaration.unionAnnotation(): KSAnnotation?`, `internal fun KSAnnotation.memberTypes(): List<KSType>?` (top level in `MemberResolver.kt`).
  - `internal class UnionWriter(codeGenerator: CodeGenerator) { fun write(model: UnionModel, sources: List<KSFile>) }`.

This is a pure refactor: the existing test suite is the safety net. Move code, do not change messages.

- [ ] **Step 1: Run the existing suite to record the baseline**

Run: `./gradlew :processor-tests:test --console=plain`
Expected: `BUILD SUCCESSFUL`, all tests pass.

- [ ] **Step 2: Create `Naming.kt`**

```kotlin
package com.github.fcat97.unionkt.processor

import com.github.fcat97.unionkt.Union

internal val UNION_ANNOTATION_NAME: String = requireNotNull(Union::class.qualifiedName)
internal val UNION_ANNOTATION_SIMPLE_NAME: String = requireNotNull(Union::class.simpleName)

internal const val TYPES_ARGUMENT = "types"
internal const val SPEC_SUFFIX = "Spec"
internal const val CASE_PREFIX = "On"
internal const val FACTORY_PREFIX = "on"
internal const val VALUE_NAME = "value"

/**
 * The union a marker generates: its name minus the `Spec` suffix, or null when the
 * name does not end in `Spec` or *is* `Spec`.
 */
internal fun unionNameOf(markerName: String): String? =
    if (markerName.endsWith(SPEC_SUFFIX) && markerName.length > SPEC_SUFFIX.length) {
        markerName.dropLast(SPEC_SUFFIX.length)
    } else {
        null
    }
```

- [ ] **Step 3: Create `UnionModel.kt`**

```kotlin
package com.github.fcat97.unionkt.processor

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.TypeName

/** One case of a union. */
internal data class UnionMember(
    /** Case suffix: `On<simpleName>`, `on<simpleName>`. */
    val simpleName: String,
    /** Fully-qualified name, used in diagnostics. */
    val qualifiedName: String,
    /** The type the case stores. */
    val typeName: TypeName,
)

/** Everything [UnionWriter] needs to emit one union. */
internal data class UnionModel(
    val markerName: String,
    val unionType: ClassName,
    val visibility: KModifier,
    val members: List<UnionMember>,
)
```

- [ ] **Step 4: Create `MemberResolver.kt`**

```kotlin
package com.github.fcat97.unionkt.processor

import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.squareup.kotlinpoet.ksp.toTypeName

/** Works out the cases of a union from its marker's `@Union` arguments. */
internal class MemberResolver(private val logger: KSPLogger) {

    /** The marker's members in declaration order, or null after reporting why there are none. */
    fun resolve(marker: KSClassDeclaration, markerName: String): List<UnionMember>? {
        val annotation = marker.unionAnnotation() ?: run {
            logger.error("Unable to read the @Union annotation on '$markerName'.", marker)
            return null
        }
        val declaredTypes = annotation.memberTypes() ?: run {
            logger.error(
                "Unable to read the 'types' argument of @Union on '$markerName'.",
                marker,
            )
            return null
        }
        if (declaredTypes.isEmpty()) {
            logger.error(
                "@Union on '$markerName' declares no member types. A union needs at least one.",
                marker,
            )
            return null
        }

        val members = declaredTypes.map { type -> resolveMember(type, markerName, marker) ?: return null }
        if (!reportCollisions(members, markerName, marker)) return null
        return members
    }

    /** Reports every simple-name clash; true when there were none. */
    private fun reportCollisions(
        members: List<UnionMember>,
        markerName: String,
        marker: KSClassDeclaration,
    ): Boolean {
        val collisions = members.groupBy(UnionMember::simpleName).filterValues { it.size > 1 }
        collisions.forEach { (simpleName, clashing) ->
            logger.error(
                "@Union on '$markerName' has ${clashing.size} member types whose simple name " +
                    "is '$simpleName' (${clashing.joinToString { it.qualifiedName }}), which " +
                    "would generate clashing '$CASE_PREFIX$simpleName' cases. Use a typealias " +
                    "or wrapper type to disambiguate them.",
                marker,
            )
        }
        return collisions.isEmpty()
    }

    private fun resolveMember(
        type: KSType,
        markerName: String,
        marker: KSClassDeclaration,
    ): UnionMember? {
        if (type.isError) {
            logger.error(
                "@Union on '$markerName' references a type that could not be resolved. " +
                    "Check the import.",
                marker,
            )
            return null
        }

        val declaration = type.declaration
        // A `KClass` literal cannot carry type arguments, so a generic member arrives with
        // its parameters unresolved (`List<T>`). Star-projecting keeps the emitted code valid.
        val resolved = if (declaration is KSClassDeclaration && declaration.typeParameters.isNotEmpty()) {
            declaration.asStarProjectedType()
        } else {
            type
        }

        val simpleName = declaration.simpleName.asString()
        val qualifiedName = declaration.qualifiedName?.asString() ?: simpleName

        return UnionMember(
            simpleName = simpleName,
            qualifiedName = qualifiedName,
            typeName = resolved.toTypeName(),
        )
    }
}

internal fun KSClassDeclaration.unionAnnotation(): KSAnnotation? = annotations.firstOrNull {
    it.shortName.asString() == UNION_ANNOTATION_SIMPLE_NAME &&
        it.annotationType.resolve().declaration.qualifiedName?.asString() == UNION_ANNOTATION_NAME
}

/** Reads the `vararg types: KClass<*>` argument, which KSP models as a list of [KSType]. */
internal fun KSAnnotation.memberTypes(): List<KSType>? {
    val argument = arguments.firstOrNull { it.name?.asString() == TYPES_ARGUMENT } ?: return null
    return when (val value = argument.value) {
        is KSType -> listOf(value)
        is List<*> -> value.filterIsInstance<KSType>().takeIf { it.size == value.size }
        is Array<*> -> value.filterIsInstance<KSType>().takeIf { it.size == value.size }
        else -> null
    }
}
```

- [ ] **Step 5: Create `UnionWriter.kt`**

```kotlin
package com.github.fcat97.unionkt.processor

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.symbol.KSFile
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.ksp.writeTo

/**
 * Emits the source file for one [UnionModel].
 *
 * Works purely from the model: every decision about *what* the union contains has
 * already been made by [UnionProcessor] and [MemberResolver].
 */
internal class UnionWriter(private val codeGenerator: CodeGenerator) {

    fun write(model: UnionModel, sources: List<KSFile>) {
        val unionType = model.unionType

        val union = TypeSpec.interfaceBuilder(unionType)
            .addModifiers(model.visibility, KModifier.SEALED)
            .addKdoc(
                "A union of %L.\n\nGenerated from the [%L] marker; `when` over this type is " +
                    "exhaustively checked by the compiler.",
                model.members.joinToString { "[${it.simpleName}]" },
                model.markerName,
            )

        val companion = TypeSpec.companionObjectBuilder()

        model.members.forEach { member ->
            val caseName = CASE_PREFIX + member.simpleName

            union.addType(
                TypeSpec.classBuilder(caseName)
                    .addModifiers(KModifier.DATA)
                    .primaryConstructor(
                        FunSpec.constructorBuilder()
                            .addParameter(VALUE_NAME, member.typeName)
                            .build(),
                    )
                    .addProperty(
                        PropertySpec.builder(VALUE_NAME, member.typeName)
                            .initializer(VALUE_NAME)
                            .build(),
                    )
                    .addSuperinterface(unionType)
                    .build(),
            )

            companion.addFunction(
                FunSpec.builder(FACTORY_PREFIX + member.simpleName)
                    .addParameter(VALUE_NAME, member.typeName)
                    .returns(unionType)
                    .addStatement("return %T(%N)", unionType.nestedClass(caseName), VALUE_NAME)
                    .build(),
            )
        }

        val fileSpec = FileSpec.builder(unionType)
            .addFileComment("Generated by unionKt from @Union on %L. Do not edit.", model.markerName)
            .addType(union.addType(companion.build()).build())
            .build()

        fileSpec.writeTo(
            codeGenerator = codeGenerator,
            dependencies = Dependencies(aggregating = false, *sources.toTypedArray()),
        )
    }
}
```

- [ ] **Step 6: Rewrite `UnionProcessor.kt`**

Replace the whole file with:

```kotlin
package com.github.fcat97.unionkt.processor

import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.Visibility
import com.google.devtools.ksp.validate
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.KModifier

/**
 * Generates a sealed-interface union for every `@Union`-annotated Spec marker.
 *
 * This class validates the marker itself; [MemberResolver] works out the cases and
 * [UnionWriter] emits the file. Every precondition is reported through
 * [KSPLogger.error] and the marker is skipped. Nothing is ever guessed: if the
 * processor cannot determine the union name or its members with certainty, it fails
 * the compilation instead.
 */
internal class UnionProcessor(
    codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
) : SymbolProcessor {

    private val memberResolver = MemberResolver(logger)
    private val writer = UnionWriter(codeGenerator)

    /** Guards against two markers in one package resolving to the same union. */
    private val generated = mutableSetOf<String>()

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val symbols = resolver.getSymbolsWithAnnotation(UNION_ANNOTATION_NAME).toList()
        // The two-argument validate(predicate, enableNewFeatures) only exists in KSP
        // 2.3.12+. This single-argument form is present in every 2.x release, so the
        // processor stays binary-compatible across the whole line.
        @Suppress("DEPRECATION")
        val (resolvable, deferred) = symbols.partition { it.validate() }

        resolvable.forEach { symbol ->
            if (symbol is KSClassDeclaration) {
                generateUnion(symbol)
            } else {
                // @Union targets CLASS, so this is unreachable in practice.
                logger.error("@Union may only be applied to an interface declaration.", symbol)
            }
        }

        return deferred
    }

    private fun generateUnion(marker: KSClassDeclaration) {
        val markerName = marker.simpleName.asString()

        if (marker.classKind != ClassKind.INTERFACE) {
            val kind = marker.classKind.type
            val article = if (kind.first() in "aeiou") "an" else "a"
            logger.error(
                "@Union may only be applied to an interface, but '$markerName' is $article " +
                    "$kind. The marker exists purely to name the union; " +
                    "declare it as 'interface $markerName'.",
                marker,
            )
            return
        }

        if (marker.typeParameters.isNotEmpty()) {
            logger.error(
                "@Union marker '$markerName' declares type parameters, which the generated " +
                    "union cannot carry. Remove them.",
                marker,
            )
            return
        }

        val unionName = unionNameOf(markerName) ?: run {
            logger.error(
                "@Union marker '$markerName' must be named '<Union>$SPEC_SUFFIX' — KSP cannot " +
                    "modify an existing declaration, so it generates the union from the marker's " +
                    "name minus the '$SPEC_SUFFIX' suffix. Rename it to e.g. '${markerName}$SPEC_SUFFIX'.",
                marker,
            )
            return
        }

        val packageName = marker.packageName.asString()
        if (packageName.isBlank()) {
            logger.error(
                "@Union marker '$markerName' must live in a named package; the generated union " +
                    "is emitted into the marker's package and the default package is not addressable.",
                marker,
            )
            return
        }

        val qualifiedUnionName = "$packageName.$unionName"
        if (!generated.add(qualifiedUnionName)) {
            logger.error(
                "@Union marker '$markerName' would generate '$qualifiedUnionName', which another " +
                    "marker in the same package already generates. Rename one of them.",
                marker,
            )
            return
        }

        val members = memberResolver.resolve(marker, markerName) ?: return

        // Computed last so the private -> internal warning is only emitted for a marker
        // that actually produces a union.
        val visibility = unionVisibilityOf(marker, markerName) ?: return

        writer.write(
            model = UnionModel(
                markerName = markerName,
                unionType = ClassName(packageName, unionName),
                visibility = visibility,
                members = members,
            ),
            sources = listOfNotNull(marker.containingFile),
        )
    }

    /**
     * Maps the marker's visibility onto the union.
     *
     * `private` is deliberately promoted to `internal`: the union is emitted into its
     * own file (KSP can only create files, never extend an existing one), and a
     * `private` top-level declaration there would be invisible to the very file that
     * declared the marker.
     */
    private fun unionVisibilityOf(marker: KSClassDeclaration, markerName: String): KModifier? =
        when (val visibility = marker.getVisibility()) {
            Visibility.PUBLIC -> KModifier.PUBLIC
            Visibility.INTERNAL -> KModifier.INTERNAL
            Visibility.PRIVATE -> {
                logger.warn(
                    "@Union marker '$markerName' is private; the generated union is emitted as " +
                        "'internal' because a private top-level declaration in the generated file " +
                        "would be invisible to '${marker.containingFile?.fileName ?: "the marker's file"}'.",
                    marker,
                )
                KModifier.INTERNAL
            }

            else -> {
                logger.error(
                    "@Union marker '$markerName' has unsupported visibility " +
                        "'${visibility.name.lowercase()}'. Use public, internal or private.",
                    marker,
                )
                null
            }
        }
}
```

- [ ] **Step 7: Run the full build**

Run: `./gradlew build --console=plain`
Expected: `BUILD SUCCESSFUL`; every `:processor-tests` test passes and `:sample` compiles. If any test fails, the refactor changed behaviour — compare against `git show HEAD:processor/src/main/kotlin/com/github/fcat97/unionkt/processor/UnionProcessor.kt` and fix before continuing.

- [ ] **Step 8: Commit**

```bash
git add processor/src/main/kotlin/com/github/fcat97/unionkt/processor/
git commit -m "Split UnionProcessor into resolver, writer and model

Pure refactor ahead of the ergonomics work: validation stays in the
processor, member resolution moves to MemberResolver and code emission
to UnionWriter, connected by a plain UnionModel."
```

---

### Task 2: Generic unions

**Files:**
- Modify: `processor/src/main/kotlin/com/github/fcat97/unionkt/processor/UnionModel.kt`
- Modify: `processor/src/main/kotlin/com/github/fcat97/unionkt/processor/MemberResolver.kt` (the `resolve` function)
- Modify: `processor/src/main/kotlin/com/github/fcat97/unionkt/processor/UnionProcessor.kt`
- Modify (full rewrite): `processor/src/main/kotlin/com/github/fcat97/unionkt/processor/UnionWriter.kt`
- Modify: `processor-tests/src/test/kotlin/com/github/fcat97/unionkt/processor/UnionCompilation.kt` (add `call`)
- Modify: `processor-tests/src/test/kotlin/com/github/fcat97/unionkt/processor/UnionProcessorErrorTest.kt` (delete one test)
- Test: `processor-tests/src/test/kotlin/com/github/fcat97/unionkt/processor/UnionGenericsTest.kt`

**Interfaces:**
- Consumes (Task 1): `UnionModel`, `UnionMember`, `MemberResolver`, `UnionWriter.write(model, sources)`, `unionNameOf`, constants.
- Produces:
  - `internal data class UnionTypeParameter(val name: String, val bounds: List<TypeName>)`.
  - `UnionMember` gains `val typeParameter: UnionTypeParameter? = null` (non-null ⇔ the case is one of the union's own type parameters; its `typeName` is then the bare `TypeVariableName(name)`).
  - `UnionModel` gains `val typeParameters: List<UnionTypeParameter> = emptyList()`.
  - `MemberResolver.resolve(marker: KSClassDeclaration, markerName: String, typeParameters: List<UnionTypeParameter>): List<UnionMember>?`.
  - Private helpers in `UnionWriter.kt` used by Tasks 3–4: `UnionMember.caseName`, `UnionMember.handlerName`, `UnionModel.caseClassName(member)`, `UnionModel.selfType()`, `UnionModel.caseSuperType(member)`, `UnionModel.nothingType()`, `UnionTypeParameter.declaredVariable()`, `UnionTypeParameter.functionVariable()`.
  - Test harness: `UnionCompilationResult.call(className: String, functionName: String): Any?`.

- [ ] **Step 1: Add `call` to the test harness**

In `UnionCompilation.kt`, add this method to `UnionCompilationResult`, directly after `generatedFileNames()`:

```kotlin
    /**
     * Runs a top-level, no-argument function from the compiled sources and returns its
     * result, e.g. `call("test.UseKt", "verify")`. A failing `check` inside it fails the test.
     */
    fun call(className: String, functionName: String): Any? =
        result.classLoader.loadClass(className).getMethod(functionName).invoke(null)
```

- [ ] **Step 2: Write the failing tests**

Create `UnionGenericsTest.kt`:

```kotlin
package com.github.fcat97.unionkt.processor

import com.tschuchort.compiletesting.SourceFile
import kotlin.test.Test
import kotlin.test.assertContains

/** Generic unions: the marker's type parameters become cases. */
class UnionGenericsTest {

    private val either = SourceFile.kotlin(
        "Either.kt",
        """
        package test

        import com.github.fcat97.unionkt.Union

        @Union
        interface EitherSpec<L, R>
        """.trimIndent(),
    )

    @Test
    fun `each type parameter becomes a covariant case`() {
        val generated = compileWithUnionProcessor(either).assertSucceeded().generated("Either.kt")

        assertContains(generated, "public sealed interface Either<out L, out R>")
        assertContains(generated, "public data class OnL<out L>(")
        assertContains(generated, "Either<L, Nothing>")
        assertContains(generated, "public data class OnR<out R>(")
        assertContains(generated, "Either<Nothing, R>")
        assertContains(generated, "public fun <L> onL(`value`: L): Either<L, Nothing> = OnL(`value`)")
    }

    @Test
    fun `cases are assignable to any parameterisation without casts`() {
        compileWithUnionProcessor(
            either,
            SourceFile.kotlin(
                "Use.kt",
                """
                package test

                fun left(): Either<String, Int> = Either.onL("e")
                fun right(): Either<String, Int> = Either.OnR(1)

                fun verify() {
                    val described = listOf(left(), right()).map { e ->
                        when (e) {
                            is Either.OnL -> "left " + e.value.length
                            is Either.OnR -> "right " + (e.value + 1)
                        }
                    }
                    check(described == listOf("left 1", "right 2")) { described.toString() }
                }
                """.trimIndent(),
            ),
        ).assertSucceeded().call("test.UseKt", "verify")
    }

    @Test
    fun `bounds carry over to the union`() {
        val result = compileWithUnionProcessor(
            SourceFile.kotlin(
                "Num.kt",
                """
                package test

                import com.github.fcat97.unionkt.Union

                @Union
                interface NumSpec<T : Number>

                fun ok(): Num<Int> = Num.onT(1)
                """.trimIndent(),
            ),
        ).assertSucceeded()

        assertContains(result.generated("Num.kt"), "public sealed interface Num<out T : Number>")
    }

    @Test
    fun `a type argument outside the bound fails to compile`() {
        // If the compiler words this differently, use the fragment from the actual output;
        // what matters is that the failure is the bound violation.
        compileWithUnionProcessor(
            SourceFile.kotlin(
                "NumBad.kt",
                """
                package test

                import com.github.fcat97.unionkt.Union

                @Union
                interface NumSpec<T : Number>

                fun bad(): Num<String> = Num.onT("x")
                """.trimIndent(),
            ),
        ).assertFailedWith("not within its bounds")
    }

    @Test
    fun `a mixed concrete and generic union works`() {
        compileWithUnionProcessor(
            SourceFile.kotlin(
                "Parsed.kt",
                """
                package test

                import com.github.fcat97.unionkt.Union

                @Union(String::class)
                interface ParsedSpec<T>

                fun raw(): Parsed<Int> = Parsed.onString("oops")
                fun parsed(): Parsed<Int> = Parsed.onT(41)

                fun verify() {
                    val described = listOf(raw(), parsed()).map { p ->
                        when (p) {
                            is Parsed.OnString -> "raw " + p.value.length
                            is Parsed.OnT -> "parsed " + (p.value + 1)
                        }
                    }
                    check(described == listOf("raw 4", "parsed 42")) { described.toString() }
                }
                """.trimIndent(),
            ),
        ).assertSucceeded().call("test.ParsedKt", "verify")
    }

    @Test
    fun `an in type parameter is rejected`() {
        compileWithUnionProcessor(
            SourceFile.kotlin(
                "Sink.kt",
                """
                package test

                import com.github.fcat97.unionkt.Union

                @Union
                interface SinkSpec<in T>
                """.trimIndent(),
            ),
        ).assertFailedWith(
            "@Union marker 'SinkSpec' type parameter 'T' is declared 'in'",
            "Remove 'in'",
        )
    }

    @Test
    fun `a bound referring to another type parameter is rejected`() {
        // Each case class declares only its own type parameter, so `OnU<out U : T>` would
        // refer to a T that does not exist there.
        compileWithUnionProcessor(
            SourceFile.kotlin(
                "Pair.kt",
                """
                package test

                import com.github.fcat97.unionkt.Union

                @Union
                interface PairSpec<T, U : T>
                """.trimIndent(),
            ),
        ).assertFailedWith("@Union marker 'PairSpec' type parameter 'U' has a bound that refers to 'T'")
    }

    @Test
    fun `a type parameter clashing with a member's simple name is rejected`() {
        compileWithUnionProcessor(
            SourceFile.kotlin(
                "Weird.kt",
                """
                package test

                import com.github.fcat97.unionkt.Union

                @Union(kotlin.String::class)
                interface WeirdSpec<String>
                """.trimIndent(),
            ),
        ).assertFailedWith("2 member types whose simple name is 'String'")
    }
}
```

Also delete the test `marker with type parameters is rejected` (the whole `@Test fun ...` block) from `UnionProcessorErrorTest.kt` — that rule is replaced by generic unions.

- [ ] **Step 3: Run the new tests and confirm they fail**

Run: `./gradlew :processor-tests:test --tests '*UnionGenericsTest*' --console=plain`
Expected: FAIL — most with "declares type parameters, which the generated union cannot carry".

- [ ] **Step 4: Extend the model**

In `UnionModel.kt`, add a `typeParameter` field to `UnionMember`, a new `UnionTypeParameter` class, and a `typeParameters` field to `UnionModel`, so the file reads:

```kotlin
package com.github.fcat97.unionkt.processor

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.TypeName

/** One case of a union. */
internal data class UnionMember(
    /** Case suffix: `On<simpleName>`, `on<simpleName>`. The type parameter's name for a generic case. */
    val simpleName: String,
    /** Fully-qualified name, used in diagnostics. Equal to [simpleName] for a type parameter. */
    val qualifiedName: String,
    /** The type the case stores. For a type parameter, the bare `TypeVariableName`. */
    val typeName: TypeName,
    /** Non-null when the case is one of the union's own type parameters. */
    val typeParameter: UnionTypeParameter? = null,
)

/** A type parameter of the marker, and therefore of the union. Always emitted as `out`. */
internal data class UnionTypeParameter(
    val name: String,
    /** Declared upper bounds, with the implicit `Any?` removed. */
    val bounds: List<TypeName>,
)

/** Everything [UnionWriter] needs to emit one union. */
internal data class UnionModel(
    val markerName: String,
    val unionType: ClassName,
    val visibility: KModifier,
    val members: List<UnionMember>,
    val typeParameters: List<UnionTypeParameter> = emptyList(),
)
```

- [ ] **Step 5: Teach `MemberResolver.resolve` about type parameters**

In `MemberResolver.kt`, add the import `com.squareup.kotlinpoet.TypeVariableName` and replace the `resolve` function with:

```kotlin
    /**
     * The union's cases: the annotation's types in order, then the marker's type
     * parameters in declaration order. Null after reporting why there are none.
     */
    fun resolve(
        marker: KSClassDeclaration,
        markerName: String,
        typeParameters: List<UnionTypeParameter>,
    ): List<UnionMember>? {
        val annotation = marker.unionAnnotation() ?: run {
            logger.error("Unable to read the @Union annotation on '$markerName'.", marker)
            return null
        }
        val declaredTypes = annotation.memberTypes() ?: run {
            logger.error(
                "Unable to read the 'types' argument of @Union on '$markerName'.",
                marker,
            )
            return null
        }
        if (declaredTypes.isEmpty() && typeParameters.isEmpty()) {
            logger.error(
                "@Union on '$markerName' declares no member types. A union needs at least one.",
                marker,
            )
            return null
        }

        val concrete = declaredTypes.map { type -> resolveMember(type, markerName, marker) ?: return null }
        val generic = typeParameters.map { parameter ->
            UnionMember(
                simpleName = parameter.name,
                qualifiedName = parameter.name,
                typeName = TypeVariableName(parameter.name),
                typeParameter = parameter,
            )
        }
        val members = concrete + generic
        if (!reportCollisions(members, markerName, marker)) return null
        return members
    }
```

- [ ] **Step 6: Validate and resolve type parameters in `UnionProcessor`**

In `UnionProcessor.kt`:

1. Add imports:

```kotlin
import com.google.devtools.ksp.symbol.Variance
import com.squareup.kotlinpoet.ANY
import com.squareup.kotlinpoet.LambdaTypeName
import com.squareup.kotlinpoet.ParameterizedTypeName
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeVariableName
import com.squareup.kotlinpoet.WildcardTypeName
import com.squareup.kotlinpoet.ksp.toTypeParameterResolver
import com.squareup.kotlinpoet.ksp.toTypeVariableName
```

2. Delete the whole `if (marker.typeParameters.isNotEmpty()) { ... }` block in `generateUnion`.

3. Replace

```kotlin
        val members = memberResolver.resolve(marker, markerName) ?: return
```

with

```kotlin
        val typeParameters = resolveTypeParameters(marker, markerName) ?: return
        val members = memberResolver.resolve(marker, markerName, typeParameters) ?: return
```

4. In the `writer.write(...)` call, add `typeParameters = typeParameters,` after `members = members,` inside `UnionModel(...)`.

5. Add these members to the class, after `generateUnion`:

```kotlin
    /**
     * The marker's type parameters, which become both the union's type parameters and
     * cases. Null after reporting every problem.
     */
    private fun resolveTypeParameters(
        marker: KSClassDeclaration,
        markerName: String,
    ): List<UnionTypeParameter>? {
        val resolver = marker.typeParameters.toTypeParameterResolver()
        val names = marker.typeParameters.map { it.name.asString() }.toSet()
        var valid = true

        val parameters = marker.typeParameters.map { parameter ->
            val name = parameter.name.asString()

            if (parameter.variance == Variance.CONTRAVARIANT) {
                logger.error(
                    "@Union marker '$markerName' type parameter '$name' is declared 'in', but a " +
                        "union case stores a $name, which only an 'out' or invariant parameter " +
                        "allows. Remove 'in'.",
                    marker,
                )
                valid = false
            }

            val bounds = parameter.toTypeVariableName(resolver).bounds.filter { it != NULLABLE_ANY }
            val foreign = bounds.flatMap { it.referencedTypeVariables() }.filter { it != name && it in names }.distinct()
            if (foreign.isNotEmpty()) {
                logger.error(
                    "@Union marker '$markerName' type parameter '$name' has a bound that refers to " +
                        "${foreign.joinToString { "'$it'" }}. Each case class declares only its own " +
                        "type parameter, so such a bound cannot be expressed. Remove the reference.",
                    marker,
                )
                valid = false
            }

            UnionTypeParameter(name = name, bounds = bounds)
        }

        return parameters.takeIf { valid }
    }

    /** Names of every type variable mentioned anywhere inside this type. */
    private fun TypeName.referencedTypeVariables(): Set<String> = when (this) {
        // Deliberately not recursing into a variable's own bounds: `T : Comparable<T>` would loop.
        is TypeVariableName -> setOf(name)
        is ParameterizedTypeName -> typeArguments.flatMapTo(mutableSetOf()) { it.referencedTypeVariables() }
        is WildcardTypeName -> (inTypes + outTypes).flatMapTo(mutableSetOf()) { it.referencedTypeVariables() }
        is LambdaTypeName ->
            (listOfNotNull(receiver) + parameters.map { it.type } + returnType)
                .flatMapTo(mutableSetOf()) { it.referencedTypeVariables() }
        else -> emptySet()
    }

    private companion object {
        val NULLABLE_ANY: TypeName = ANY.copy(nullable = true)
    }
```

- [ ] **Step 7: Rewrite `UnionWriter.kt` to be generics-aware**

Replace the whole file with:

```kotlin
package com.github.fcat97.unionkt.processor

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.symbol.KSFile
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.NOTHING
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.TypeVariableName
import com.squareup.kotlinpoet.ksp.writeTo

/**
 * Emits the source file for one [UnionModel].
 *
 * Works purely from the model: every decision about *what* the union contains has
 * already been made by [UnionProcessor] and [MemberResolver].
 */
internal class UnionWriter(private val codeGenerator: CodeGenerator) {

    fun write(model: UnionModel, sources: List<KSFile>) {
        val file = FileSpec.builder(model.unionType)
            .addFileComment("Generated by unionKt from @Union on %L. Do not edit.", model.markerName)
            .addType(unionInterface(model))

        file.build().writeTo(
            codeGenerator = codeGenerator,
            dependencies = Dependencies(aggregating = false, *sources.toTypedArray()),
        )
    }

    private fun unionInterface(model: UnionModel): TypeSpec {
        val union = TypeSpec.interfaceBuilder(model.unionType)
            .addModifiers(model.visibility, KModifier.SEALED)
            .addTypeVariables(model.typeParameters.map { it.declaredVariable() })
            .addKdoc(
                "A union of %L.\n\nGenerated from the [%L] marker; `when` over this type is " +
                    "exhaustively checked by the compiler.",
                model.members.joinToString { "[${it.simpleName}]" },
                model.markerName,
            )

        val companion = TypeSpec.companionObjectBuilder()
        model.members.forEach { member ->
            union.addType(caseClass(model, member))
            companion.addFunction(factory(model, member))
        }
        return union.addType(companion.build()).build()
    }

    /** `data class OnL<out L>(val value: L) : Either<L, Nothing>`, or `OnInt(...) : Result`. */
    private fun caseClass(model: UnionModel, member: UnionMember): TypeSpec =
        TypeSpec.classBuilder(member.caseName)
            .addModifiers(KModifier.DATA)
            .apply { member.typeParameter?.let { addTypeVariable(it.declaredVariable()) } }
            .primaryConstructor(
                FunSpec.constructorBuilder()
                    .addParameter(VALUE_NAME, member.typeName)
                    .build(),
            )
            .addProperty(
                PropertySpec.builder(VALUE_NAME, member.typeName)
                    .initializer(VALUE_NAME)
                    .build(),
            )
            .addSuperinterface(model.caseSuperType(member))
            .build()

    /** `fun <L> onL(value: L): Either<L, Nothing> = OnL(value)`, inside the companion. */
    private fun factory(model: UnionModel, member: UnionMember): FunSpec =
        FunSpec.builder(member.handlerName)
            .apply { member.typeParameter?.let { addTypeVariable(it.functionVariable()) } }
            .addParameter(VALUE_NAME, member.typeName)
            .returns(model.caseSuperType(member))
            .addStatement("return %T(%N)", model.caseClassName(member), VALUE_NAME)
            .build()
}

// Naming and typing helpers shared by every emitter in this file.

private val UnionMember.caseName: String get() = CASE_PREFIX + simpleName

/** The `on<Case>` name used by companion factories and `fold` parameters. */
private val UnionMember.handlerName: String get() = FACTORY_PREFIX + simpleName

private fun UnionModel.caseClassName(member: UnionMember): ClassName =
    unionType.nestedClass(member.caseName)

private fun UnionModel.parameterized(argument: (UnionTypeParameter) -> TypeName): TypeName =
    if (typeParameters.isEmpty()) unionType else unionType.parameterizedBy(typeParameters.map(argument))

/** `Either<L, R>`: the union over its own type variables, the receiver of every helper. */
private fun UnionModel.selfType(): TypeName = parameterized { TypeVariableName(it.name) }

/** `Either<Nothing, Nothing>`: what a concrete case is, and what conversions return. */
private fun UnionModel.nothingType(): TypeName = parameterized { NOTHING }

/** `Either<L, Nothing>` for the `L` case; [nothingType] for a concrete case. */
private fun UnionModel.caseSuperType(member: UnionMember): TypeName =
    parameterized { if (it == member.typeParameter) TypeVariableName(it.name) else NOTHING }

/** As declared on the union and on its case class: always `out`, bounds kept. */
private fun UnionTypeParameter.declaredVariable(): TypeVariableName =
    TypeVariableName(name, bounds, KModifier.OUT)

/** As declared on a function or extension property, where variance is not allowed. */
private fun UnionTypeParameter.functionVariable(): TypeVariableName =
    TypeVariableName(name, bounds)
```

Note: `nothingType()` and `selfType()` are unused until Tasks 3–4. If the build warns about unused private functions, that is expected; do not delete them.

- [ ] **Step 8: Run the generics tests**

Run: `./gradlew :processor-tests:test --tests '*UnionGenericsTest*' --console=plain`
Expected: PASS, all 8 tests. If `a type argument outside the bound fails to compile` fails only on the message fragment, read the actual compiler output in the failure and change the fragment to the bound-violation wording it shows.

- [ ] **Step 9: Run the full build**

Run: `./gradlew build --console=plain`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 10: Commit**

```bash
git add processor/src/main/kotlin/com/github/fcat97/unionkt/processor/ processor-tests/src/test/kotlin/com/github/fcat97/unionkt/processor/
git commit -m "Support generic unions: marker type parameters become cases

@Union interface EitherSpec<L, R> generates sealed interface
Either<out L, out R> with covariant OnL/OnR cases that use Nothing for
the other parameters, so no casts are needed. Bounds carry over; 'in'
parameters and bounds referring to sibling parameters are errors."
```

---

### Task 3: Constructor functions, `fold` and case accessors

**Files:**
- Modify: `processor/src/main/kotlin/com/github/fcat97/unionkt/processor/Naming.kt`
- Modify: `processor/src/main/kotlin/com/github/fcat97/unionkt/processor/UnionWriter.kt`
- Test: `processor-tests/src/test/kotlin/com/github/fcat97/unionkt/processor/UnionHelpersTest.kt`

**Interfaces:**
- Consumes (Task 2): writer helpers `caseName`, `handlerName`, `caseClassName`, `selfType`, `caseSuperType`, `functionVariable`; `UnionModel.typeParameters`; `UnionMember.typeParameter`; harness `call`.
- Produces:
  - `internal fun accessorStem(simpleName: String): String` and `internal fun freeTypeVariableName(taken: Set<String>): String` in `Naming.kt`.
  - Generated API for every union `X`: top-level `fun X(value: T): X` per concrete case (with `@JvmName("XOf<Case>")`), `inline fun <..., R> X<...>.fold(on<Case>: (T) -> R, ...): R`, `val X.is<Case>: Boolean`, `val X.<stem>OrNull: T?`.

- [ ] **Step 1: Write the failing tests**

Create `UnionHelpersTest.kt`:

```kotlin
package com.github.fcat97.unionkt.processor

import com.tschuchort.compiletesting.SourceFile
import kotlin.test.Test
import kotlin.test.assertContains

/** Constructor functions, fold and case accessors, generated for every union. */
class UnionHelpersTest {

    private val result = SourceFile.kotlin(
        "Result.kt",
        """
        package test

        import com.github.fcat97.unionkt.Union

        data class User(val name: String)

        @Union(Int::class, String::class, User::class)
        interface ResultSpec
        """.trimIndent(),
    )

    @Test
    fun `constructor functions pick the case by argument type`() {
        compileWithUnionProcessor(
            result,
            SourceFile.kotlin(
                "Use.kt",
                """
                package test

                fun verify() {
                    check(Result(5) == Result.OnInt(5))
                    check(Result("hi") == Result.OnString("hi"))
                    check(Result(User("Ada")) == Result.OnUser(User("Ada")))
                }
                """.trimIndent(),
            ),
        ).assertSucceeded().call("test.UseKt", "verify")
    }

    @Test
    fun `fold is an exhaustive expression`() {
        compileWithUnionProcessor(
            result,
            SourceFile.kotlin(
                "Use.kt",
                """
                package test

                fun verify() {
                    val values = listOf(Result(5), Result("hi"), Result(User("Ada")))
                    val folded = values.map { r ->
                        r.fold(onInt = { it + 1 }, onString = { it.length }, onUser = { it.name.length * 10 })
                    }
                    check(folded == listOf(6, 2, 30)) { folded.toString() }
                }
                """.trimIndent(),
            ),
        ).assertSucceeded().call("test.UseKt", "verify")
    }

    @Test
    fun `accessors report the active case`() {
        compileWithUnionProcessor(
            result,
            SourceFile.kotlin(
                "Use.kt",
                """
                package test

                fun verify() {
                    val r = Result(5)
                    check(r.isInt && !r.isString && !r.isUser)
                    check(r.intOrNull == 5)
                    check(r.stringOrNull == null && r.userOrNull == null)
                }
                """.trimIndent(),
            ),
        ).assertSucceeded().call("test.UseKt", "verify")
    }

    @Test
    fun `the most specific constructor overload wins`() {
        compileWithUnionProcessor(
            SourceFile.kotlin(
                "Text.kt",
                """
                package test

                import com.github.fcat97.unionkt.Union

                @Union(CharSequence::class, String::class)
                interface TextSpec

                fun verify() {
                    check(Text("x") == Text.OnString("x"))
                    check(Text(StringBuilder("x")) is Text.OnCharSequence)
                }
                """.trimIndent(),
            ),
        ).assertSucceeded().call("test.TextKt", "verify")
    }

    @Test
    fun `members with the same JVM erasure get distinct constructor JVM names`() {
        // List and MutableList both erase to java.util.List; without @JvmName the two
        // constructor functions would be a platform declaration clash.
        compileWithUnionProcessor(
            SourceFile.kotlin(
                "Lists.kt",
                """
                package test

                import com.github.fcat97.unionkt.Union

                @Union(List::class, MutableList::class)
                interface ListsSpec

                fun verify() {
                    check(Lists(listOf(1)) is Lists.OnList)
                    check(Lists(mutableListOf(1)) is Lists.OnMutableList)
                }
                """.trimIndent(),
            ),
        ).assertSucceeded().call("test.ListsKt", "verify")
    }

    @Test
    fun `accessor names are decapitalised Kotlin style`() {
        compileWithUnionProcessor(
            SourceFile.kotlin(
                "Stem.kt",
                """
                package test

                import com.github.fcat97.unionkt.Union

                @Union(DoubleArray::class, java.net.URL::class)
                interface StemSpec

                fun verify() {
                    val s = Stem(doubleArrayOf(1.0))
                    check(s.isDoubleArray && !s.isURL)
                    check(s.doubleArrayOrNull?.size == 1)
                    check(s.urlOrNull == null)
                }
                """.trimIndent(),
            ),
        ).assertSucceeded().call("test.StemKt", "verify")
    }

    @Test
    fun `helpers work on a generic union`() {
        compileWithUnionProcessor(
            SourceFile.kotlin(
                "Either.kt",
                """
                package test

                import com.github.fcat97.unionkt.Union

                @Union
                interface EitherSpec<L, R>

                fun verify() {
                    val e: Either<String, Int> = Either.onR(41)
                    check(e.fold(onL = { it.length }, onR = { it + 1 }) == 42)
                    check(e.isR && !e.isL)
                    check(e.rOrNull == 41 && e.lOrNull == null)
                }
                """.trimIndent(),
            ),
        ).assertSucceeded().call("test.EitherKt", "verify")
    }

    @Test
    fun `fold renames its result type when the union already uses T`() {
        val compiled = compileWithUnionProcessor(
            SourceFile.kotlin(
                "Box.kt",
                """
                package test

                import com.github.fcat97.unionkt.Union

                @Union
                interface BoxSpec<T>

                fun verify() {
                    val b: Box<Int> = Box.onT(1)
                    check(b.fold(onT = { it + 1 }) == 2)
                }
                """.trimIndent(),
            ),
        ).assertSucceeded()

        assertContains(compiled.generated("Box.kt"), "fun <T, T1> Box<T>.fold(")
        compiled.call("test.BoxKt", "verify")
    }

    @Test
    fun `a mixed union gets constructor functions for concrete cases only`() {
        val compiled = compileWithUnionProcessor(
            SourceFile.kotlin(
                "Parsed.kt",
                """
                package test

                import com.github.fcat97.unionkt.Union

                @Union(String::class)
                interface ParsedSpec<T>

                fun verify() {
                    val p: Parsed<Int> = Parsed("raw")
                    check(p == Parsed.OnString("raw"))
                }
                """.trimIndent(),
            ),
        ).assertSucceeded()

        val generated = compiled.generated("Parsed.kt")
        assertContains(generated, "public fun Parsed(`value`: String): Parsed<Nothing>")
        kotlin.test.assertFalse(generated.contains("fun <T> Parsed("), generated)
        compiled.call("test.ParsedKt", "verify")
    }

    @Test
    fun `helpers take the union's visibility`() {
        val generated = compileWithUnionProcessor(
            SourceFile.kotlin(
                "Hidden.kt",
                """
                package test

                import com.github.fcat97.unionkt.Union

                @Union(Int::class)
                internal interface HiddenSpec
                """.trimIndent(),
            ),
        ).assertSucceeded().generated("Hidden.kt")

        assertContains(generated, "internal fun Hidden(`value`: Int): Hidden")
        assertContains(generated, "internal inline fun <T> Hidden.fold(")
        assertContains(generated, "internal val Hidden.isInt: Boolean")
        assertContains(generated, "internal val Hidden.intOrNull: Int?")
    }
}
```

- [ ] **Step 2: Run the new tests and confirm they fail**

Run: `./gradlew :processor-tests:test --tests '*UnionHelpersTest*' --console=plain`
Expected: FAIL — compilation errors such as `Unresolved reference 'fold'` / `'isInt'`, or "Expression 'Result' of type ... cannot be invoked as a function".

- [ ] **Step 3: Add the naming functions**

Append to `Naming.kt`:

```kotlin
/**
 * The stem of a case's `<stem>OrNull` accessor: the simple name decapitalised Kotlin
 * style. A leading run of capitals is lowercased, except for its last letter when that
 * letter starts the next word: `Int → int`, `DoubleArray → doubleArray`, `URL → url`,
 * `URLParser → urlParser`, `L → l`.
 */
internal fun accessorStem(simpleName: String): String {
    val capitals = simpleName.takeWhile(Char::isUpperCase).length
    if (capitals == 0) return simpleName
    val startsNextWord = capitals > 1 && capitals < simpleName.length && simpleName[capitals].isLowerCase()
    val lowered = if (startsNextWord) capitals - 1 else capitals
    return simpleName.take(lowered).lowercase() + simpleName.drop(lowered)
}

/** The first of `T`, `T1`, `T2`, … that is not in [taken]. */
internal fun freeTypeVariableName(taken: Set<String>): String =
    generateSequence(0) { it + 1 }
        .map { if (it == 0) "T" else "T$it" }
        .first { it !in taken }
```

- [ ] **Step 4: Emit the helpers**

In `UnionWriter.kt`:

1. Add imports:

```kotlin
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.BOOLEAN
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.LambdaTypeName
```

2. Replace the `write` function with:

```kotlin
    fun write(model: UnionModel, sources: List<KSFile>) {
        val file = FileSpec.builder(model.unionType)
            .addFileComment("Generated by unionKt from @Union on %L. Do not edit.", model.markerName)
            .addType(unionInterface(model))

        constructorFunctions(model).forEach(file::addFunction)
        file.addFunction(fold(model))
        model.members.forEach { member -> accessors(model, member).forEach(file::addProperty) }

        file.build().writeTo(
            codeGenerator = codeGenerator,
            dependencies = Dependencies(aggregating = false, *sources.toTypedArray()),
        )
    }
```

3. Add these methods to the `UnionWriter` class, after `factory`:

```kotlin
    /**
     * `fun Result(value: Int): Result = Result.OnInt(value)`, one per concrete case.
     *
     * Type-parameter cases get none: `fun <L> Either(value: L)` and `fun <R> Either(value: R)`
     * would share a JVM signature and be ambiguous at every call site.
     */
    private fun constructorFunctions(model: UnionModel): List<FunSpec> =
        model.members.filter { it.typeParameter == null }.map { member ->
            FunSpec.builder(model.unionType.simpleName)
                .addModifiers(model.visibility)
                // Mapped types such as List and MutableList erase to the same JVM type, so
                // every overload gets its own JVM name. Kotlin callers never see it.
                .addAnnotation(
                    AnnotationSpec.builder(JvmName::class)
                        .addMember("%S", model.unionType.simpleName + "Of" + member.simpleName)
                        .build(),
                )
                .addParameter(VALUE_NAME, member.typeName)
                .returns(model.caseSuperType(member))
                .addStatement("return %T(%N)", model.caseClassName(member), VALUE_NAME)
                .build()
        }

    /**
     * `inline fun <T> Result.fold(onInt: (Int) -> T, …): T`. Every handler is required,
     * so it is as exhaustive as `when`. An extension because an `inline` function with a
     * body cannot be an interface member.
     */
    private fun fold(model: UnionModel): FunSpec {
        val result = TypeVariableName(freeTypeVariableName(model.typeParameters.map { it.name }.toSet()))

        val body = CodeBlock.builder().beginControlFlow("return when (this)")
        model.members.forEach { member ->
            body.addStatement("is %T -> %N(%N)", model.caseClassName(member), member.handlerName, VALUE_NAME)
        }
        body.endControlFlow()

        return FunSpec.builder("fold")
            .addModifiers(model.visibility, KModifier.INLINE)
            .addTypeVariables(model.typeParameters.map { it.functionVariable() } + result)
            .receiver(model.selfType())
            .apply {
                model.members.forEach { member ->
                    addParameter(member.handlerName, LambdaTypeName.get(null, member.typeName, returnType = result))
                }
            }
            .returns(result)
            .addCode(body.build())
            .build()
    }

    /** `val Result.isInt: Boolean` and `val Result.intOrNull: Int?`. */
    private fun accessors(model: UnionModel, member: UnionMember): List<PropertySpec> {
        val caseClass = model.caseClassName(member)
        val typeVariables = model.typeParameters.map { it.functionVariable() }

        val isCase = PropertySpec.builder("is" + member.simpleName, BOOLEAN)
            .addModifiers(model.visibility)
            .addTypeVariables(typeVariables)
            .receiver(model.selfType())
            .getter(FunSpec.getterBuilder().addStatement("return this is %T", caseClass).build())
            .build()

        val orNull = PropertySpec.builder(accessorStem(member.simpleName) + "OrNull", member.typeName.copy(nullable = true))
            .addModifiers(model.visibility)
            .addTypeVariables(typeVariables)
            .receiver(model.selfType())
            .getter(
                FunSpec.getterBuilder()
                    .addStatement("return if (this is %T) %N else null", caseClass, VALUE_NAME)
                    .build(),
            )
            .build()

        return listOf(isCase, orNull)
    }
```

- [ ] **Step 5: Run the helper tests**

Run: `./gradlew :processor-tests:test --tests '*UnionHelpersTest*' --console=plain`
Expected: PASS, all 10 tests.

- [ ] **Step 6: Run the full build**

Run: `./gradlew build --console=plain`
Expected: `BUILD SUCCESSFUL`. The existing generation test `generates a sealed interface with a case and factory per member` must still pass unchanged — the companion factories are untouched.

- [ ] **Step 7: Commit**

```bash
git add processor/src/main/kotlin/com/github/fcat97/unionkt/processor/ processor-tests/src/test/kotlin/com/github/fcat97/unionkt/processor/UnionHelpersTest.kt
git commit -m "Generate constructor functions, fold and case accessors

Every union now gets Result(5)-style constructor functions (one per
concrete case, each with a distinct @JvmName), an exhaustive inline
fold, and isX / xOrNull extension accessors, all following the union's
visibility and working on generic unions."
```

---

### Task 4: Flattening within a module

**Files:**
- Modify: `processor/src/main/kotlin/com/github/fcat97/unionkt/processor/UnionModel.kt`
- Modify (full rewrite): `processor/src/main/kotlin/com/github/fcat97/unionkt/processor/MemberResolver.kt`
- Modify: `processor/src/main/kotlin/com/github/fcat97/unionkt/processor/UnionProcessor.kt`
- Modify: `processor/src/main/kotlin/com/github/fcat97/unionkt/processor/UnionWriter.kt`
- Test: `processor-tests/src/test/kotlin/com/github/fcat97/unionkt/processor/UnionFlatteningTest.kt`

**Interfaces:**
- Consumes (Tasks 1–3): everything above.
- Produces:
  - `UnionMember` gains `val via: String? = null` (the chain of nested markers a flattened member came through, e.g. `"ShapeSpec"` or `"ShapeSpec → PolygonSpec"`).
  - `internal data class FlattenedUnion(val unionType: ClassName, val visibility: KModifier, val members: List<UnionMember>)`.
  - `UnionModel` gains `val flattened: List<FlattenedUnion> = emptyList()`.
  - `internal data class Resolution(val members: List<UnionMember>, val flattened: List<FlattenedUnion>, val sources: List<KSFile>)`.
  - `MemberResolver.resolve(marker, markerName, typeParameters): Resolution?` (return type changes from `List<UnionMember>?`).
  - Generated API: `fun Nested.to<Outer>(): Outer` per flattened union, direct and transitive.

- [ ] **Step 1: Write the failing tests**

Create `UnionFlatteningTest.kt`:

```kotlin
package com.github.fcat97.unionkt.processor

import com.tschuchort.compiletesting.SourceFile
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

/** A member that is itself a @Union marker contributes its cases directly. */
class UnionFlatteningTest {

    private val shapes = SourceFile.kotlin(
        "Shapes.kt",
        """
        package test

        import com.github.fcat97.unionkt.Union

        data class Circle(val radius: Int)
        data class Square(val side: Int)

        @Union(Circle::class, Square::class)
        interface ShapeSpec
        """.trimIndent(),
    )

    @Test
    fun `a nested marker's cases are inlined and a conversion is generated`() {
        compileWithUnionProcessor(
            shapes,
            SourceFile.kotlin(
                "Item.kt",
                """
                package test

                import com.github.fcat97.unionkt.Union

                @Union(Int::class, ShapeSpec::class)
                interface ItemSpec

                fun describe(i: Item): String = when (i) {
                    is Item.OnInt -> "int"
                    is Item.OnCircle -> "circle"
                    is Item.OnSquare -> "square"
                }

                fun verify() {
                    check(Shape(Circle(1)).toItem() == Item.OnCircle(Circle(1)))
                    check(Shape(Square(2)).toItem() == Item(Square(2)))
                }
                """.trimIndent(),
            ),
        ).assertSucceeded().call("test.ItemKt", "verify")
    }

    @Test
    fun `flattening is transitive and converts from every level`() {
        compileWithUnionProcessor(
            SourceFile.kotlin(
                "Levels.kt",
                """
                package test

                import com.github.fcat97.unionkt.Union

                data class Circle(val radius: Int)
                data class Triangle(val side: Int)

                @Union(Triangle::class)
                interface PolygonSpec

                @Union(Circle::class, PolygonSpec::class)
                interface ShapeSpec

                @Union(Int::class, ShapeSpec::class)
                interface ItemSpec

                fun describe(i: Item): String = when (i) {
                    is Item.OnInt -> "int"
                    is Item.OnCircle -> "circle"
                    is Item.OnTriangle -> "triangle"
                }

                fun verify() {
                    check(Polygon(Triangle(3)).toItem() == Item.OnTriangle(Triangle(3)))
                    check(Shape(Triangle(3)).toItem() == Item.OnTriangle(Triangle(3)))
                    check(Polygon(Triangle(3)).toShape() == Shape.OnTriangle(Triangle(3)))
                }
                """.trimIndent(),
            ),
        ).assertSucceeded().call("test.LevelsKt", "verify")
    }

    @Test
    fun `overlapping nested unions merge silently`() {
        compileWithUnionProcessor(
            SourceFile.kotlin(
                "Overlap.kt",
                """
                package test

                import com.github.fcat97.unionkt.Union

                @Union(Int::class, String::class)
                interface ASpec

                @Union(String::class, Long::class)
                interface BSpec

                @Union(ASpec::class, BSpec::class)
                interface CSpec

                fun describe(c: C): String = when (c) {
                    is C.OnInt -> "int"
                    is C.OnString -> "string"
                    is C.OnLong -> "long"
                }
                """.trimIndent(),
            ),
        ).assertSucceeded().assertDoesNotWarn("more than once")
    }

    @Test
    fun `a type listed twice directly is merged with a warning`() {
        val result = compileWithUnionProcessor(
            SourceFile.kotlin(
                "Dup.kt",
                """
                package test

                import com.github.fcat97.unionkt.Union

                @Union(Int::class, Int::class)
                interface DupSpec
                """.trimIndent(),
            ),
        ).assertSucceeded()

        result.assertWarns("@Union on 'DupSpec' lists 'kotlin.Int' more than once; the duplicates are merged.")
        assertEquals(1, Regex("data class OnInt\\(").findAll(result.generated("Dup.kt")).count())
    }

    @Test
    fun `a simple-name clash through flattening names the path`() {
        compileWithUnionProcessor(
            SourceFile.kotlin(
                "Clash.kt",
                """
                package test

                import com.github.fcat97.unionkt.Union

                class User

                @Union(test.other.User::class)
                interface PeopleSpec

                @Union(User::class, PeopleSpec::class)
                interface ClashSpec
                """.trimIndent(),
            ),
            SourceFile.kotlin("OtherUser.kt", "package test.other\n\nclass User"),
        ).assertFailedWith(
            "2 member types whose simple name is 'User'",
            "test.User, test.other.User via PeopleSpec",
        )
    }

    @Test
    fun `a flattening cycle is rejected`() {
        compileWithUnionProcessor(
            SourceFile.kotlin(
                "Cycle.kt",
                """
                package test

                import com.github.fcat97.unionkt.Union

                @Union(Int::class, BSpec::class)
                interface ASpec

                @Union(String::class, ASpec::class)
                interface BSpec
                """.trimIndent(),
            ),
        ).assertFailedWith("@Union on 'ASpec' has a flattening cycle: ASpec → BSpec → ASpec")
    }

    @Test
    fun `flattening a generic marker is rejected`() {
        compileWithUnionProcessor(
            SourceFile.kotlin(
                "GenericFlatten.kt",
                """
                package test

                import com.github.fcat97.unionkt.Union

                @Union
                interface EitherSpec<L, R>

                @Union(Int::class, EitherSpec::class)
                interface ItemSpec
                """.trimIndent(),
            ),
        ).assertFailedWith("@Union on 'ItemSpec' cannot flatten generic union 'EitherSpec'")
    }

    @Test
    fun `referencing the generated union nests it as one case`() {
        compileWithUnionProcessor(
            shapes,
            SourceFile.kotlin(
                "Nest.kt",
                """
                package test

                import com.github.fcat97.unionkt.Union

                @Union(Int::class, Shape::class)
                interface NestSpec

                fun describe(n: Nest): String = when (n) {
                    is Nest.OnInt -> "int"
                    is Nest.OnShape -> "shape"
                }
                """.trimIndent(),
            ),
        ).assertSucceeded()
    }

    @Test
    fun `a conversion takes the stricter visibility of the two unions`() {
        val result = compileWithUnionProcessor(
            SourceFile.kotlin(
                "Vis.kt",
                """
                package test

                import com.github.fcat97.unionkt.Union

                @Union(Int::class)
                internal interface SecretSpec

                @Union(String::class)
                interface OpenSpec

                @Union(SecretSpec::class, OpenSpec::class)
                interface MixSpec
                """.trimIndent(),
            ),
        ).assertSucceeded()

        val generated = result.generated("Mix.kt")
        assertContains(generated, "internal fun Secret.toMix(): Mix")
        assertContains(generated, "public fun Open.toMix(): Mix")
    }

    @Test
    fun `a generic union can flatten a concrete one`() {
        compileWithUnionProcessor(
            shapes,
            SourceFile.kotlin(
                "Wrap.kt",
                """
                package test

                import com.github.fcat97.unionkt.Union

                @Union(ShapeSpec::class)
                interface WrapSpec<T>

                fun wrapped(): Wrap<Int> = Shape(Circle(1)).toWrap()
                """.trimIndent(),
            ),
        ).assertSucceeded()
    }
}
```

- [ ] **Step 2: Run the new tests and confirm they fail**

Run: `./gradlew :processor-tests:test --tests '*UnionFlatteningTest*' --console=plain`
Expected: FAIL — e.g. `'when' expression must be exhaustive` / `Unresolved reference 'OnCircle'` (today `ShapeSpec::class` becomes an `OnShapeSpec` case), and the error tests find no matching message. `referencing the generated union nests it as one case` may already pass; that is fine.

- [ ] **Step 3: Extend the model**

In `UnionModel.kt`:

1. Add a `via` field as the last property of `UnionMember`:

```kotlin
    /** The chain of nested markers this member was flattened in through, e.g. `ShapeSpec → PolygonSpec`. */
    val via: String? = null,
```

2. Add, after `UnionTypeParameter`:

```kotlin
/** A union whose cases were inlined into another; each one gets a `toX()` conversion. */
internal data class FlattenedUnion(
    val unionType: ClassName,
    val visibility: KModifier,
    /** The flattened union's own cases, in its order. */
    val members: List<UnionMember>,
)
```

3. Add as the last property of `UnionModel`:

```kotlin
    val flattened: List<FlattenedUnion> = emptyList(),
```

- [ ] **Step 4: Rewrite `MemberResolver.kt` with flattening**

Replace the whole file with:

```kotlin
package com.github.fcat97.unionkt.processor

import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Visibility
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.TypeVariableName
import com.squareup.kotlinpoet.ksp.toTypeName

/** The resolved cases of a union, plus everything flattening pulled in. */
internal data class Resolution(
    val members: List<UnionMember>,
    /** Every union inlined into this one, directly or transitively. */
    val flattened: List<FlattenedUnion>,
    /** Source files the generated union depends on: its marker's and every local flattened marker's. */
    val sources: List<KSFile>,
)

/**
 * Works out the cases of a union from its marker's `@Union` arguments.
 *
 * A member whose declaration is itself a `@Union` marker is flattened: its cases are
 * inlined, recursively. Members are then merged by exact type; different types that
 * share a simple name are an error, because they would generate clashing cases.
 */
internal class MemberResolver(private val logger: KSPLogger) {

    /**
     * The union's cases: the annotation's types in order with nested markers expanded in
     * place, then the marker's type parameters. Null after reporting every problem.
     */
    fun resolve(
        marker: KSClassDeclaration,
        markerName: String,
        typeParameters: List<UnionTypeParameter>,
    ): Resolution? {
        val declaredTypes = declaredTypesOf(marker, markerName, reportOn = marker) ?: return null
        if (declaredTypes.isEmpty() && typeParameters.isEmpty()) {
            logger.error(
                "@Union on '$markerName' declares no member types. A union needs at least one.",
                marker,
            )
            return null
        }

        val flattened = linkedMapOf<ClassName, FlattenedUnion>()
        val sources = linkedSetOf<KSFile>()
        marker.containingFile?.let(sources::add)

        val expanded = expand(
            root = marker,
            rootName = markerName,
            declaredTypes = declaredTypes,
            path = listOf(marker),
            flattened = flattened,
            sources = sources,
        ) ?: return null

        reportDirectDuplicates(expanded, markerName, marker)

        val generic = typeParameters.map { parameter ->
            UnionMember(
                simpleName = parameter.name,
                qualifiedName = parameter.name,
                typeName = TypeVariableName(parameter.name),
                typeParameter = parameter,
            )
        }
        val members = (expanded + generic).distinctBy(UnionMember::typeName)
        if (!reportCollisions(members, markerName, marker)) return null

        return Resolution(members, flattened.values.toList(), sources.toList())
    }

    /**
     * The members declared by one marker, nested markers expanded in place, duplicates
     * kept (the caller merges them). [path] runs from the root marker to the marker whose
     * [declaredTypes] these are; it drives cycle detection and the `via` in diagnostics.
     */
    private fun expand(
        root: KSClassDeclaration,
        rootName: String,
        declaredTypes: List<KSType>,
        path: List<KSClassDeclaration>,
        flattened: MutableMap<ClassName, FlattenedUnion>,
        sources: MutableSet<KSFile>,
    ): List<UnionMember>? {
        val via = path.drop(1).takeIf { it.isNotEmpty() }?.joinToString(" → ") { it.simpleName.asString() }
        val members = mutableListOf<UnionMember>()

        for (type in declaredTypes) {
            if (type.isError) {
                logger.error(
                    "@Union on '$rootName' references a type that could not be resolved. " +
                        "Check the import.",
                    root,
                )
                return null
            }

            val nested = (type.declaration as? KSClassDeclaration)?.takeIf { it.unionAnnotation() != null }
            if (nested == null) {
                members += concreteMember(type, via)
            } else {
                members += flatten(root, rootName, nested, path, flattened, sources) ?: return null
            }
        }
        return members
    }

    /** Inlines a nested marker's members and records it for a `toX()` conversion. */
    private fun flatten(
        root: KSClassDeclaration,
        rootName: String,
        nested: KSClassDeclaration,
        path: List<KSClassDeclaration>,
        flattened: MutableMap<ClassName, FlattenedUnion>,
        sources: MutableSet<KSFile>,
    ): List<UnionMember>? {
        val nestedName = nested.simpleName.asString()

        if (path.any { it.qualifiedName?.asString() == nested.qualifiedName?.asString() }) {
            val cycle = (path + nested).joinToString(" → ") { it.simpleName.asString() }
            logger.error(
                "@Union on '$rootName' has a flattening cycle: $cycle. Remove one of the references.",
                root,
            )
            return null
        }

        if (nested.typeParameters.isNotEmpty()) {
            logger.error(
                "@Union on '$rootName' cannot flatten generic union '$nestedName': a class " +
                    "literal cannot say which type arguments are meant. List the concrete member " +
                    "types instead, or reference the generated union to keep it as a single case.",
                root,
            )
            return null
        }

        val nestedUnionName = unionNameOf(nestedName) ?: run {
            logger.error(
                "@Union on '$rootName' cannot flatten '$nestedName': it is annotated with @Union " +
                    "but its name does not end in '$SPEC_SUFFIX', so its union's name is unknown.",
                root,
            )
            return null
        }

        val nestedTypes = declaredTypesOf(nested, nestedName, reportOn = root) ?: return null
        val members = expand(root, rootName, nestedTypes, path + nested, flattened, sources) ?: return null

        val unionType = ClassName(nested.packageName.asString(), nestedUnionName)
        flattened.getOrPut(unionType) {
            FlattenedUnion(unionType, visibilityOf(nested), members.distinctBy(UnionMember::typeName))
        }
        nested.containingFile?.let(sources::add)
        return members
    }

    private fun declaredTypesOf(
        spec: KSClassDeclaration,
        specName: String,
        reportOn: KSClassDeclaration,
    ): List<KSType>? {
        val annotation = spec.unionAnnotation() ?: run {
            logger.error("Unable to read the @Union annotation on '$specName'.", reportOn)
            return null
        }
        return annotation.memberTypes() ?: run {
            logger.error("Unable to read the 'types' argument of @Union on '$specName'.", reportOn)
            null
        }
    }

    /** The visibility of a marker's generated union: `private` markers yield `internal` unions. */
    private fun visibilityOf(spec: KSClassDeclaration): KModifier =
        if (spec.getVisibility() == Visibility.PUBLIC) KModifier.PUBLIC else KModifier.INTERNAL

    /** Warns when the root marker itself lists one type more than once (likely a typo). */
    private fun reportDirectDuplicates(
        expanded: List<UnionMember>,
        markerName: String,
        marker: KSClassDeclaration,
    ) {
        expanded.filter { it.via == null }
            .groupBy(UnionMember::typeName)
            .filterValues { it.size > 1 }
            .keys
            .forEach { typeName ->
                logger.warn(
                    "@Union on '$markerName' lists '$typeName' more than once; the duplicates are merged.",
                    marker,
                )
            }
    }

    /** Reports every simple-name clash; true when there were none. */
    private fun reportCollisions(
        members: List<UnionMember>,
        markerName: String,
        marker: KSClassDeclaration,
    ): Boolean {
        val collisions = members.groupBy(UnionMember::simpleName).filterValues { it.size > 1 }
        collisions.forEach { (simpleName, clashing) ->
            logger.error(
                "@Union on '$markerName' has ${clashing.size} member types whose simple name " +
                    "is '$simpleName' (${clashing.joinToString { it.describe() }}), which " +
                    "would generate clashing '$CASE_PREFIX$simpleName' cases. Use a typealias " +
                    "or wrapper type to disambiguate them.",
                marker,
            )
        }
        return collisions.isEmpty()
    }

    private fun UnionMember.describe(): String = if (via == null) qualifiedName else "$qualifiedName via $via"

    private fun concreteMember(type: KSType, via: String?): UnionMember {
        val declaration = type.declaration
        // A `KClass` literal cannot carry type arguments, so a generic member arrives with
        // its parameters unresolved (`List<T>`). Star-projecting keeps the emitted code valid.
        val resolved = if (declaration is KSClassDeclaration && declaration.typeParameters.isNotEmpty()) {
            declaration.asStarProjectedType()
        } else {
            type
        }

        val simpleName = declaration.simpleName.asString()
        return UnionMember(
            simpleName = simpleName,
            qualifiedName = declaration.qualifiedName?.asString() ?: simpleName,
            typeName = resolved.toTypeName(),
            via = via,
        )
    }
}

internal fun KSClassDeclaration.unionAnnotation(): KSAnnotation? = annotations.firstOrNull {
    it.shortName.asString() == UNION_ANNOTATION_SIMPLE_NAME &&
        it.annotationType.resolve().declaration.qualifiedName?.asString() == UNION_ANNOTATION_NAME
}

/** Reads the `vararg types: KClass<*>` argument, which KSP models as a list of [KSType]. */
internal fun KSAnnotation.memberTypes(): List<KSType>? {
    val argument = arguments.firstOrNull { it.name?.asString() == TYPES_ARGUMENT } ?: return null
    return when (val value = argument.value) {
        is KSType -> listOf(value)
        is List<*> -> value.filterIsInstance<KSType>().takeIf { it.size == value.size }
        is Array<*> -> value.filterIsInstance<KSType>().takeIf { it.size == value.size }
        else -> null
    }
}
```

- [ ] **Step 5: Pass the resolution through `UnionProcessor`**

In `UnionProcessor.generateUnion`, replace

```kotlin
        val members = memberResolver.resolve(marker, markerName, typeParameters) ?: return
```

with

```kotlin
        val resolution = memberResolver.resolve(marker, markerName, typeParameters) ?: return
```

and replace the `writer.write(...)` call with:

```kotlin
        writer.write(
            model = UnionModel(
                markerName = markerName,
                unionType = ClassName(packageName, unionName),
                visibility = visibility,
                members = resolution.members,
                typeParameters = typeParameters,
                flattened = resolution.flattened,
            ),
            sources = resolution.sources,
        )
```

- [ ] **Step 6: Emit the conversions**

In `UnionWriter.kt`:

1. In `write`, directly after the line `model.members.forEach { member -> accessors(model, member).forEach(file::addProperty) }`, add:

```kotlin
        model.flattened.forEach { nested -> file.addFunction(conversion(model, nested)) }
```

2. Add this method to the class, after `accessors`:

```kotlin
    /**
     * `fun Shape.toItem(): Item`, mapping each of the flattened union's cases onto the
     * same-typed case here. Visibility is the stricter of the two unions; a generic
     * union's conversion returns `Item<Nothing, …>`, which covariance makes assignable
     * to any parameterisation.
     */
    private fun conversion(model: UnionModel, nested: FlattenedUnion): FunSpec {
        val body = CodeBlock.builder().beginControlFlow("return when (this)")
        nested.members.forEach { member ->
            body.addStatement(
                "is %T -> %T(%N)",
                nested.unionType.nestedClass(member.caseName),
                model.caseClassName(member),
                VALUE_NAME,
            )
        }
        body.endControlFlow()

        val visibility =
            if (model.visibility == KModifier.INTERNAL || nested.visibility == KModifier.INTERNAL) {
                KModifier.INTERNAL
            } else {
                KModifier.PUBLIC
            }

        return FunSpec.builder("to" + model.unionType.simpleName)
            .addModifiers(visibility)
            .receiver(nested.unionType)
            .returns(model.nothingType())
            .addCode(body.build())
            .build()
    }
```

- [ ] **Step 7: Run the flattening tests**

Run: `./gradlew :processor-tests:test --tests '*UnionFlatteningTest*' --console=plain`
Expected: PASS, all 10 tests.

- [ ] **Step 8: Run the full build**

Run: `./gradlew build --console=plain`
Expected: `BUILD SUCCESSFUL`. In particular the existing `member types sharing a simple name are rejected` and `three member types sharing a simple name are all reported` still pass (no `via` on direct members).

- [ ] **Step 9: Commit**

```bash
git add processor/src/main/kotlin/com/github/fcat97/unionkt/processor/ processor-tests/src/test/kotlin/com/github/fcat97/unionkt/processor/UnionFlatteningTest.kt
git commit -m "Flatten nested @Union markers into their parent union

A member that is itself a @Union marker contributes its cases directly,
recursively, with a generated toX() conversion from every flattened
union. Identical types merge (a direct duplicate warns); same-name
clashes name the path; cycles and generic markers are errors.
Referencing the generated type still nests it as a single case."
```

---

### Task 5: Cross-module flattening (`BINARY` retention)

**Files:**
- Modify: `annotations/src/main/kotlin/com/github/fcat97/unionkt/Union.kt` (retention line only)
- Modify: `processor-tests/src/test/kotlin/com/github/fcat97/unionkt/processor/UnionCompilation.kt`
- Test: `processor-tests/src/test/kotlin/com/github/fcat97/unionkt/processor/UnionCrossModuleTest.kt`

**Interfaces:**
- Consumes (Task 4): flattening in `MemberResolver`; harness `call`.
- Produces:
  - `compileWithUnionProcessor(vararg sources: SourceFile, classpath: List<File> = emptyList())`.
  - `UnionCompilationResult.outputDirectory: File` (the compiled classes, usable as a dependency).

- [ ] **Step 1: Extend the harness**

In `UnionCompilation.kt`:

1. Change the `compileWithUnionProcessor` signature and add the classpath line:

```kotlin
internal fun compileWithUnionProcessor(
    vararg sources: SourceFile,
    classpath: List<File> = emptyList(),
): UnionCompilationResult {
    val compilation = KotlinCompilation().apply {
        this.sources = sources.toList()
        // Puts the :annotations module (and kotlin-stdlib) on the compiled sources'
        // classpath, so `import com.github.fcat97.unionkt.Union` resolves.
        inheritClassPath = true
        // Extra dependencies, e.g. a "library" compiled by an earlier call.
        classpaths = classpath
        useKsp2()
        configureKsp {
            symbolProcessorProviders += UnionProcessorProvider()
        }
    }

    val result = compilation.compile()
    return UnionCompilationResult(result, compilation.kspSourcesDir)
}
```

2. Add to `UnionCompilationResult`, after `messages`:

```kotlin
    /** The compiled classes, to pass as `classpath` to a later compilation. */
    val outputDirectory: File get() = result.outputDirectory
```

- [ ] **Step 2: Write the failing test**

Create `UnionCrossModuleTest.kt`:

```kotlin
package com.github.fcat97.unionkt.processor

import com.tschuchort.compiletesting.SourceFile
import kotlin.test.Test

/**
 * Flattening a marker that comes from a dependency. This only works because `@Union`
 * has BINARY retention: with SOURCE retention the annotation is gone from the library's
 * class files and the marker would become a plain `OnShapeSpec` case.
 */
class UnionCrossModuleTest {

    @Test
    fun `a marker from a dependency is flattened`() {
        val library = compileWithUnionProcessor(
            SourceFile.kotlin(
                "Shapes.kt",
                """
                package lib

                import com.github.fcat97.unionkt.Union

                data class Circle(val radius: Int)
                data class Square(val side: Int)

                @Union(Circle::class, Square::class)
                interface ShapeSpec
                """.trimIndent(),
            ),
        ).assertSucceeded()

        compileWithUnionProcessor(
            SourceFile.kotlin(
                "Item.kt",
                """
                package app

                import com.github.fcat97.unionkt.Union
                import lib.Circle
                import lib.Shape
                import lib.ShapeSpec
                import lib.Square

                @Union(Int::class, ShapeSpec::class)
                interface ItemSpec

                fun describe(i: Item): String = when (i) {
                    is Item.OnInt -> "int"
                    is Item.OnCircle -> "circle"
                    is Item.OnSquare -> "square"
                }

                fun verify() {
                    check(Shape(Circle(1)).toItem() == Item.OnCircle(Circle(1)))
                    check(Shape(Square(2)).toItem() == Item.OnSquare(Square(2)))
                }
                """.trimIndent(),
            ),
            classpath = listOf(library.outputDirectory),
        ).assertSucceeded().call("app.ItemKt", "verify")
    }
}
```

- [ ] **Step 3: Run it and confirm it fails**

Run: `./gradlew :processor-tests:test --tests '*UnionCrossModuleTest*' --console=plain`
Expected: FAIL in the consumer compilation — `Unresolved reference 'OnCircle'` or `'when' expression must be exhaustive`, because under `SOURCE` retention `ShapeSpec` arrives without its annotation.

- [ ] **Step 4: Switch `@Union` to `BINARY` retention**

In `Union.kt`, replace

```kotlin
@Retention(AnnotationRetention.SOURCE)
```

with

```kotlin
// BINARY, not SOURCE: the annotation must survive into class files so a marker from a
// dependency can be flattened. It is still invisible to runtime reflection.
@Retention(AnnotationRetention.BINARY)
```

(The KDoc is rewritten in Task 6.)

- [ ] **Step 5: Run the test**

Run: `./gradlew :processor-tests:test --tests '*UnionCrossModuleTest*' --console=plain`
Expected: PASS.

- [ ] **Step 6: Run the full build**

Run: `./gradlew build --console=plain`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Commit**

```bash
git add annotations/src/main/kotlin/com/github/fcat97/unionkt/Union.kt processor-tests/src/test/kotlin/com/github/fcat97/unionkt/processor/
git commit -m "Keep @Union in class files so dependency markers can be flattened

With SOURCE retention the annotation vanished from a library's class
files, so a downstream union could not see that a referenced marker was
a union. BINARY keeps it (still invisible to reflection); a two-module
test locks the behaviour in."
```

---

### Task 6: Sample, KDoc and README

**Files:**
- Modify: `annotations/src/main/kotlin/com/github/fcat97/unionkt/Union.kt` (KDoc)
- Create: `sample/src/main/kotlin/com/github/fcat97/unionkt/sample/EitherSample.kt`
- Create: `sample/src/main/kotlin/com/github/fcat97/unionkt/sample/DrawableSample.kt`
- Modify: `sample/src/main/kotlin/com/github/fcat97/unionkt/sample/Main.kt`
- Modify: `README.md`

**Interfaces:**
- Consumes: the generated API from Tasks 2–5.
- Produces: documentation only; `:sample` must compile and run.

- [ ] **Step 1: Add the sample sources**

Create `EitherSample.kt`:

```kotlin
package com.github.fcat97.unionkt.sample

import com.github.fcat97.unionkt.Union

/** A generic union: the marker's type parameters become the cases `OnL` and `OnR`. */
@Union
public interface EitherSpec<L, R>

/** No casts: `Either.onR(it)` is an `Either<Nothing, Int>`, assignable to `Either<String, Int>`. */
public fun parseAge(text: String): Either<String, Int> =
    text.toIntOrNull()?.let { Either.onR(it) } ?: Either.onL("not a number: $text")

public fun describeAge(age: Either<String, Int>): String =
    age.fold(onL = { "error: $it" }, onR = { "age $it" })
```

Create `DrawableSample.kt`:

```kotlin
package com.github.fcat97.unionkt.sample

import com.github.fcat97.unionkt.Union

public data class Circle(val radius: Double)
public data class Square(val side: Double)

@Union(Circle::class, Square::class)
public interface ShapeSpec

/** Flattened: `Drawable` has the cases OnUser, OnCircle and OnSquare, plus `Shape.toDrawable()`. */
@Union(User::class, ShapeSpec::class)
public interface DrawableSpec

public fun label(drawable: Drawable): String = when (drawable) {
    is Drawable.OnUser -> "user ${drawable.value.name}"
    is Drawable.OnCircle -> "circle r=${drawable.value.radius}"
    is Drawable.OnSquare -> "square side=${drawable.value.side}"
}
```

Replace `Main.kt` with:

```kotlin
package com.github.fcat97.unionkt.sample

public fun main() {
    val values = listOf(
        Result.onInt(5),
        Result("hello"),
        Result(User(id = 1, name = "Ada")),
    )
    values.forEach { println(describe(it)) }
    println(values.first().intOrNull)

    println(size(Payload.onBoolean(true)))
    println(size(Payload.onDoubleArray(doubleArrayOf(1.0, 2.0))))
    println(size(Payload.onUser(User(id = 2, name = "Grace"))))

    println(describeAge(parseAge("42")))
    println(describeAge(parseAge("forty-two")))

    println(label(Shape(Circle(radius = 1.5)).toDrawable()))
    println(label(Drawable(Square(side = 2.0))))
}
```

- [ ] **Step 2: Build and run the sample**

Run: `./gradlew :sample:build --console=plain && java -cp "sample/build/classes/kotlin/main:$(find ~/.gradle/caches/modules-2 -name 'kotlin-stdlib-2.4.20.jar' | head -1)" com.github.fcat97.unionkt.sample.MainKt`
Expected output:

```
int=5
string=hello
user=Ada
5
1
2
5
age 42
error: not a number: forty-two
circle r=1.5
square side=2.0
```

- [ ] **Step 3: Rewrite the `@Union` KDoc**

In `Union.kt`, replace the whole KDoc block above `@Target` with:

```kotlin
/**
 * Marks a **Spec marker interface** whose union type should be generated.
 *
 * KSP can only *add* code, never modify an existing declaration, so the
 * interface you annotate is not the union itself — it is a marker that names it.
 * Annotate `FooSpec` and the processor generates a real `sealed interface Foo`
 * in the same package:
 *
 * ```
 * @Union(Int::class, String::class, User::class)
 * private interface ResultSpec
 *
 * // generated:
 * //   internal sealed interface Result {
 * //       data class OnInt(val value: Int) : Result
 * //       data class OnString(val value: String) : Result
 * //       data class OnUser(val value: User) : Result
 * //       companion object {
 * //           fun onInt(value: Int): Result = OnInt(value)
 * //           fun onString(value: String): Result = OnString(value)
 * //           fun onUser(value: User): Result = OnUser(value)
 * //       }
 * //   }
 * //   fun Result(value: Int): Result          // one per concrete case
 * //   inline fun <T> Result.fold(onInt: (Int) -> T, onString: (String) -> T, onUser: (User) -> T): T
 * //   val Result.isInt: Boolean; val Result.intOrNull: Int?   // one pair per case
 * ```
 *
 * **Generic unions.** The marker's type parameters become cases:
 * `@Union interface EitherSpec<L, R>` generates `sealed interface Either<out L, out R>`
 * with cases `OnL` and `OnR`. [types] may be empty when the marker has type parameters.
 *
 * **Flattening.** A member that is itself a `@Union` marker contributes its cases
 * directly: `@Union(Int::class, ShapeSpec::class)` gets one case per type in `Shape`,
 * plus a generated `Shape.toX()` conversion. Reference the generated type
 * (`Shape::class`) instead to keep it as a single case.
 *
 * Requirements, all of which are enforced with a compile error rather than a
 * silent fallback:
 *
 * - the annotated declaration must be an `interface`;
 * - its simple name must end in `Spec` and be longer than `Spec` itself;
 * - it must live in a named (non-default) package;
 * - it must declare at least one member type or type parameter, and no type
 *   parameter may be declared `in`;
 * - no two *different* members may share a simple name (the same type listed
 *   twice is merged, with a warning).
 *
 * The generated union mirrors the marker's visibility, except that a `private`
 * marker yields an `internal` union — a `private` top-level declaration in the
 * generated file would be invisible to the file that declared the marker.
 *
 * `BINARY` retention keeps this annotation in class files (it is not visible to
 * runtime reflection), so a marker from a dependency can be flattened.
 *
 * @param types the member types of the union, in declaration order.
 */
```

Leave the `// BINARY, not SOURCE …` comment and `@Retention(AnnotationRetention.BINARY)` from Task 5 in place below `@Target`.

- [ ] **Step 4: Update the README intro example**

In `README.md`, replace the first code block (under the intro paragraph) with:

````markdown
```kotlin
@Union(Int::class, String::class, User::class)
private interface ResultSpec

val r: Result = Result(5)         // picks the Int case

val out = when (r) {              // remove any branch -> compile error
    is Result.OnInt    -> r.value.toString()
    is Result.OnString -> r.value
    is Result.OnUser   -> r.value.name
}

val same = r.fold(onInt = { it.toString() }, onString = { it }, onUser = { it.name })
```
````

- [ ] **Step 5: Add the feature sections to the README**

Insert the following immediately **before** the `## Exhaustiveness guarantee` heading:

````markdown
## Helpers on every union

Next to the sealed interface and its companion factories, the generated file contains:

```kotlin
fun Result(value: Int): Result            // one constructor function per concrete case
fun Result(value: String): Result
fun Result(value: User): Result

inline fun <T> Result.fold(               // exhaustive: every handler is required
    onInt: (Int) -> T,
    onString: (String) -> T,
    onUser: (User) -> T,
): T

val Result.isInt: Boolean                 // one pair per case
val Result.intOrNull: Int?
```

- Kotlin's overload resolution picks the constructor function; with related members
  (`CharSequence` and `String`) the most specific one wins.
- Each constructor function has its own `@JvmName` (`ResultOfInt`, …), because mapped
  types such as `List` and `MutableList` share a JVM erasure.
- `fold` and the accessors are extensions: an `inline` function with a body cannot be an
  interface member.
- Accessor names decapitalise the case Kotlin-style: `DoubleArray → doubleArrayOrNull`,
  `URL → urlOrNull`.
- All helpers take the union's visibility.

## Generic unions

A marker's type parameters become cases:

```kotlin
@Union interface EitherSpec<L, R>

fun parseAge(text: String): Either<String, Int> =
    text.toIntOrNull()?.let { Either.onR(it) } ?: Either.onL("not a number")
```

generates

```kotlin
sealed interface Either<out L, out R> {
    data class OnL<out L>(val value: L) : Either<L, Nothing>
    data class OnR<out R>(val value: R) : Either<Nothing, R>
    companion object {
        fun <L> onL(value: L): Either<L, Nothing> = OnL(value)
        fun <R> onR(value: R): Either<Nothing, R> = OnR(value)
    }
}
```

plus `fold`, `isL` / `lOrNull` and `isR` / `rOrNull`. Every parameter is `out` and each case
uses `Nothing` for the others, so `Either.onL("e")` is assignable to `Either<String, Int>`
without a cast.

- Concrete and generic members mix: `@Union(String::class) interface ParsedSpec<T>` is
  `String | T`, and its concrete cases use `Nothing` for every parameter.
- Bounds carry over: `<T : Number>` gives `Either<out T : Number>`.
- Type-parameter cases have no constructor function — `fun <L> Either(value: L)` and
  `fun <R> Either(value: R)` would share a JVM signature. Use the companion factories.
- For `Either<String?, Int>`, `lOrNull` is `null` both for "not an L" and "an L holding
  null". Use `isL` to tell them apart.

## Flattening

A member that is itself a `@Union` marker contributes its cases directly, like
TypeScript's `A | B`:

```kotlin
@Union(Circle::class, Square::class) interface ShapeSpec
@Union(Int::class, ShapeSpec::class) interface ItemSpec
// Item = OnInt | OnCircle | OnSquare

fun Shape.toItem(): Item   // generated, in Item.kt
```

- **Nested markers flatten recursively.** A conversion is generated from every flattened
  union, including indirect ones.
- **Identical types merge.** Two nested unions that both contain `Timeout` give one
  `OnTimeout`. Listing a type twice directly is merged with a warning.
- **Want a single case instead?** Reference the generated type: `@Union(Int::class,
  Shape::class)` gives `OnInt | OnShape`.
- **Across modules:** a public marker from a dependency flattens like a local one. This is
  why `@Union` has `BINARY` retention.
- A conversion takes the stricter visibility of the two unions. For a generic outer union it
  returns `Item<Nothing, …>`, which is assignable to any `Item<A, B>`.
- A generic marker cannot be flattened: a class literal cannot say which type arguments are
  meant. A `private` marker can only be flattened from its own file (Kotlin's visibility
  rules); make it `internal` instead.

---
````

- [ ] **Step 6: Update the README error table and notes**

In the `## Errors` table:

1. Delete the row `| Marker declares type parameters | … |`.
2. Change the `No member types` row's situation text to `No member types and no type parameters`.
3. Add these rows at the end of the table:

```markdown
| `in` type parameter on the marker | `@Union marker 'SinkSpec' type parameter 'T' is declared 'in', but a union case stores a T …` |
| Type parameter bound refers to another type parameter | `@Union marker 'PairSpec' type parameter 'U' has a bound that refers to 'T'. …` |
| Flattening a generic marker | `@Union on 'ItemSpec' cannot flatten generic union 'EitherSpec' …` |
| Flattening cycle | `@Union on 'ASpec' has a flattening cycle: ASpec → BSpec → ASpec. …` |
| Same simple name through flattening | the clash message above, with `… via PeopleSpec` on the flattened member |
```

Directly below the table, add:

```markdown
One warning: listing the same type twice directly (`@Union(Int::class, Int::class)`) merges
the duplicates and reports `@Union on 'DupSpec' lists 'kotlin.Int' more than once; the
duplicates are merged.`
```

In the `## Modules` table, change the `:annotations` row's contents to
`` `@Union(vararg val types: KClass<*>)`, `CLASS` target, `BINARY` retention. Nothing else. ``

Replace the paragraph starting `` `@Union` has `SOURCE` retention `` with:

```markdown
`@Union` has `BINARY` retention: it is kept in class files, so a downstream module can flatten
a marker from a dependency, but it is not visible to runtime reflection.
```

In the `## Tests` → `Covered:` list, replace the first bullet's phrase `type parameters on the marker, ` with nothing (delete it), and append these bullets to the list:

```markdown
- **Helpers** — constructor functions pick the right case (including the most specific
  overload and `List`/`MutableList`), `fold` and the accessors are run, not just compiled,
  and follow the union's visibility.
- **Generic unions** — covariant cases assignable without casts, bounds carried over and
  enforced, mixed concrete + generic unions, and the `in` / sibling-bound errors.
- **Flattening** — single-level, transitive, overlapping, direct duplicates, clash via
  flattening, cycles, generic markers, nesting via the generated type, conversion
  visibility, and a **two-module** compilation that flattens a marker from a dependency.
```

- [ ] **Step 7: Run the full build**

Run: `./gradlew build --console=plain`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 8: Commit**

```bash
git add annotations/src/main/kotlin/com/github/fcat97/unionkt/Union.kt sample/src/main/kotlin/com/github/fcat97/unionkt/sample/ README.md
git commit -m "Document helpers, generic unions and flattening

Sample gains a generic Either and a flattened Drawable union; the
@Union KDoc and README describe the new API, errors and BINARY
retention."
```
