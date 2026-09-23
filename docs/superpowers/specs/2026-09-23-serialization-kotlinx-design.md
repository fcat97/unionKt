# Extension API and kotlinx.serialization plugin — design

Sub-project 2 of 4 in the unionKt roadmap:

1. Union ergonomics — shipped in 0.2.0.
2. **Serialization** (this spec) — an extension API for the processor, plus the first extension:
   kotlinx.serialization support for untagged unions.
3. `@Derive` extras on existing sealed hierarchies.
4. Intersection types, string literal unions, refined types.

Moshi and Gson extensions are follow-up sub-projects that reuse this spec's extension API.

## Goals

- Serialize a union as its bare inner value (untagged) and read it back, with kotlinx.serialization,
  in JSON.
- Keep serialization strictly opt-in and library-neutral: nothing changes for a project that does
  not add the plugin, and no serialization library is favoured by `@Union` itself.
- Give plugins the finished union model (generics, flattening, visibility already resolved) so they
  never re-parse markers.

## Non-goals

- Formats other than JSON. An untagged union is chosen by inspecting the data, which binary formats
  cannot do without a type tag.
- Moshi and Gson (follow-up sub-projects).
- Choosing a case by field presence. Cases are tried in declaration order (serde-style).
- An opt-out from the compile-time serializability check (§4.1).

## 1. Opt-in model

Serialization is enabled by adding a plugin artifact to the `ksp` configuration. No flag, no
annotation parameter:

```kotlin
dependencies {
    implementation("com.github.fcat97.unionKt:annotations:0.3.0")
    ksp("com.github.fcat97.unionKt:processor:0.3.0")
    ksp("com.github.fcat97.unionKt:serialization-kotlinx:0.3.0")   // opt-in
}
```

A separate KSP processor could not do this job: the serializer must be attached to the union with
`@Serializable(with = …)`, and a second processor cannot modify the file the main processor
generates. So plugins are **extensions of the main processor**, not processors of their own.

## 2. Extension API (`:processor-api`)

A new published module, public so that third parties can write extensions.

```kotlin
package com.github.fcat97.unionkt.api

public interface UnionExtension {
    /**
     * Extra annotations for the generated union interface. Also the place to validate:
     * report problems through env.logger.error.
     */
    public fun unionAnnotations(union: UnionInfo, env: ExtensionEnvironment): List<AnnotationSpec> = emptyList()

    /** Extra files for this union. */
    public fun generate(union: UnionInfo, env: ExtensionEnvironment) {}
}

public class UnionInfo(
    public val markerName: String,
    public val marker: KSClassDeclaration,
    public val unionType: ClassName,
    public val visibility: KModifier,                 // PUBLIC or INTERNAL
    public val typeParameters: List<TypeVariableName>, // as declared on the union: `out`, with bounds
    public val members: List<UnionMemberInfo>,         // flattened members included, in case order
    public val sources: List<KSFile>,                  // originating files for Dependencies
)

public class UnionMemberInfo(
    public val simpleName: String,
    public val typeName: TypeName,                  // the type the case stores
    public val caseClass: ClassName,                // e.g. Result.OnInt
    public val typeParameterName: String?,          // non-null for a type-parameter case (OnL)
    public val declaration: KSClassDeclaration?,    // null for a type-parameter case
)

public class ExtensionEnvironment(
    public val codeGenerator: CodeGenerator,
    public val logger: KSPLogger,
    public val resolver: Resolver,                  // the current round's resolver
)
```

`:processor-api` depends on the KSP API and KotlinPoet (`api` scope, since both appear in its
signatures).

### 2.1 Discovery

An extension is registered in
`META-INF/services/com.github.fcat97.unionkt.api.UnionExtension`. The main processor loads all
registered extensions once, at construction, with
`ServiceLoader.load(UnionExtension::class.java, UnionExtension::class.java.classLoader)`.

Verified by spike on 2026-09-23 (Gradle 9.7.1, Kotlin 2.4.20, KSP 2.3.12): in a real multi-module
build, a processor loaded this way found an extension supplied by a second `ksp(project(":ext"))`
dependency, and found none when that dependency was removed. KSP loads the whole `ksp`
configuration into one classloader.

### 2.2 Invocation

For each union, after the model is resolved:

1. Build a `UnionInfo` from the `UnionModel` (flattened members are ordinary members).
2. Call every extension's `unionAnnotations`; add all returned annotations to the union interface.
3. Write the union file.
4. Call every extension's `generate`.

If an extension throws, the processor reports
`unionKt extension '<fully.qualified.Class>' failed on '<Marker>': <message>` through
`logger.error` and continues with the next extension; it does not let the exception escape to
KSP.

## 3. The kotlinx.serialization extension (`:serialization-kotlinx`)

A new published module depending on `:processor-api` and KotlinPoet. It has no dependency on
kotlinx.serialization: it only emits code that uses it.

### 3.1 Generated code

The union gains `@Serializable(with = <Union>Serializer::class)`. A new file
`<Union>Serializer.kt` in the union's package, with the union's visibility, contains:

- **Non-generic union:** `object ResultSerializer : KSerializer<Result>`.
- **Generic union:** `class EitherSerializer<L, R>(lSerializer: KSerializer<L>, rSerializer: KSerializer<R>) : KSerializer<Either<L, R>>`,
  one constructor parameter per union type parameter, in declaration order, named
  `<decapitalised name>Serializer`. The compiler plugin supplies them.

Case serializers: a concrete member uses `serializer<T>()`; a type-parameter member uses the
matching constructor parameter.

Descriptor: `SerialDescriptor("<qualified union name>", ContextualSerializer(Any::class).descriptor)`,
with `@OptIn(ExperimentalSerializationApi::class)`. Its kind is `CONTEXTUAL`, so an outer union's
shape filter (§3.3) always tries a union nested as a case; `JsonElement`'s descriptor (kind
`SEALED`, used in the spike) would restrict a nested union to JSON objects.

Verified by spike on 2026-09-23 (kotlinx-serialization-json 1.11.0, compiler plugin 2.4.20): a
KSP-generated generic sealed interface annotated this way works as a property of a
`@Serializable` class (type-argument serializers are passed to the constructor) and at top level
via `Json.decodeFromString<Either<String, Int>>("5")`.

### 3.2 Serializing

`when` over the cases; each case writes its inner value with its case serializer. No wrapper, no
discriminator: `Result.OnInt(5)` → `5`, `Result.OnUser(User("Ada"))` → `{"name":"Ada"}`.

### 3.3 Deserializing

1. If the decoder is not a `JsonDecoder`, throw
   `SerializationException("<Union>Serializer supports JSON only; got <decoder class>.")`.
2. Read the value with `decodeJsonElement()`.
3. **Filter by shape.** Keep the cases whose serializer descriptor kind can produce that JSON
   shape. For an inline (value class) descriptor, use the kind of its single element.

   | JSON value | Descriptor kinds kept |
   | --- | --- |
   | string | `STRING`, `CHAR`, `ENUM` |
   | number | `INT`, `LONG`, `SHORT`, `BYTE`, `FLOAT`, `DOUBLE` |
   | `true` / `false` | `BOOLEAN` |
   | object | `CLASS`, `OBJECT`, `MAP`, `SEALED`, `OPEN` |
   | array | `LIST` |
   | `null` | none |

   Cases with kind `CONTEXTUAL` are kept for every non-null shape.

   Why: kotlinx accepts a quoted number for an `Int` (`Json.decodeFromString<Int>("\"5\"")`
   succeeds — observed in the spike), so without this filter `"5"` would decode to `OnInt` in an
   `Int | String` union.
4. **Try the remaining cases in declaration order** with
   `json.decodeFromJsonElement(caseSerializer, element)`; the first that does not throw wins.
5. **No match:** throw `SerializationException` naming the union, the JSON shape, and each case
   tried with its error message (or stating that no case accepts that shape).

JSON `null` never matches a case; a nullable property (`val r: Result?`) handles null before the
union serializer is reached.

## 4. Compile-time checks (in the kotlinx extension)

Reported through `logger.error` on the marker from `unionAnnotations`.

| Situation | Message (prefix) |
| --- | --- |
| A star-projected member (a generic class given as a class literal, e.g. `List::class`) | `@Union on '<Marker>' cannot serialize member 'List<*>': a class literal cannot say its element type. Wrap it in a @Serializable class.` |
| A non-serializable member class | `@Union on '<Marker>' cannot serialize member '<qualified name>': it is not @Serializable. Annotate it, or wrap it in a @Serializable class.` |
| The kotlinx runtime is missing | `serialization-kotlinx is installed, but kotlinx-serialization-core is not on the classpath. Add the kotlinx-serialization-json dependency.` |

The missing-runtime check resolves `kotlinx.serialization.Serializable` through
`env.resolver`; it is reported once per compilation, not once per union.

### 4.1 What counts as serializable

A concrete member's class is accepted when any of these hold:

- it carries `@kotlinx.serialization.Serializable` (with or without `with =`);
- it is an enum class;
- its package is `kotlin` or starts with `kotlin.`.

Everything else is rejected — including `java.*` types. The spike showed
`serializer<NotSerializable>()` compiles and only fails at runtime, so without this check the
failure would surface in production.

**Known gap:** a type made serializable only through a runtime contextual serializer or
`@file:UseSerializers` is rejected, because KSP cannot see either. Documented; the workaround is a
`@Serializable` wrapper class.

Type-parameter members are not checked; the compiler plugin checks type arguments at each use
site.

## 5. Project requirements (documented, not detectable)

- The kotlinx.serialization Gradle plugin must be applied to the module, so that
  `@Serializable(with = …)` on the union is wired into other `@Serializable` classes.
- `kotlinx-serialization-json` **1.6.3 or newer**. The only API used whose availability in 1.6.3
  is unverified is `SerialDescriptor(String, SerialDescriptor)`; the implementation runs the test
  suite against 1.6.3 to confirm it. If it is missing there, the minimum is raised and this spec
  is updated.

## 6. Modules and publishing

| Module | Published | Depends on |
| --- | --- | --- |
| `:processor-api` | yes | KSP API, KotlinPoet (`api`) |
| `:processor` | yes | adds `:processor-api` |
| `:serialization-kotlinx` | yes | `:processor-api`, KotlinPoet |
| `:serialization-kotlinx-tests` | no | test module (§7) |

Both new published modules use the same `maven-publish` setup as `:annotations` and `:processor`,
so JitPack exposes `com.github.fcat97.unionKt:processor-api:<tag>` and
`com.github.fcat97.unionKt:serialization-kotlinx:<tag>`. Release: **0.3.0**.

## 7. Testing

### 7.1 Extension API (`:processor-tests`)

A test-only extension registered in `:processor-tests`' test resources. It acts only on markers
whose name starts with `Ext` (so the existing suite is unaffected):

- `ExtAnnotatedSpec` → it returns `@kotlin.Deprecated("from extension")`; the test asserts the
  annotation is on the generated union.
- `ExtGeneratedSpec` → `generate` writes a file `ExtGeneratedExtra.kt`; the test asserts it exists
  and compiles.
- `ExtThrowsSpec` → it throws; the test asserts the `unionKt extension '…' failed on 'ExtThrowsSpec'`
  error.
- A test asserts `UnionInfo` contents (members including flattened ones, type parameters,
  visibility) by having the extension record what it received.

### 7.2 Serialization (`:serialization-kotlinx-tests`)

A separate, unpublished module, so that the kotlinx extension on its classpath does not activate in
`:processor-tests`. It compiles with the real serialization compiler plugin
(`kotlin-serialization-compiler-plugin-embeddable`, same version as Kotlin) and runs the compiled
code. The compilation harness is shared from `:processor-tests` via the `java-test-fixtures`
plugin.

- Round trips: `Int`, `String`, `Boolean`, `Double`, an object, an array member
  (`IntArray`), an enum, a value class, a generic union as a property of a `@Serializable` class,
  a generic union at top level, a flattened union, a union nested as a case (`Shape::class`).
- `"5"` decodes to `OnString` in an `Int | String` union; `5` decodes to `OnInt`.
- Declaration order decides between two object cases (`Admin` listed before `User`).
- No match: the exception names the union and the cases tried.
- JSON `null` for a non-null union property fails; for a nullable one it decodes to `null`.
- A non-JSON decoder fails with the JSON-only message (a minimal hand-written `AbstractDecoder`).
- The three compile errors of §4, and that a `kotlin.*` member and an enum member pass.
- The kotlinx version is a Gradle property (`kotlinxSerializationVersion`, default 1.11.0);
  the suite is run once with `-PkotlinxSerializationVersion=1.6.3` (§5).

### 7.3 Sample

`:sample` applies the kotlinx.serialization plugin and the extension, and round-trips a union in
`main`.

## 8. Documentation

README gains:

- **Serialization (kotlinx)**: setup (the three dependencies plus the Gradle plugin), untagged
  output, how reading chooses a case (shape filter, then declaration order), advice to list more
  specific types first, the compile-time checks and the known gap.
- **Writing an extension**: the `UnionExtension` interface, registration, what `UnionInfo` offers.
- Error table rows for §4 and for extension failures.
