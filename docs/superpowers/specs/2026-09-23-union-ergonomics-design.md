# Union ergonomics — design

Sub-project 1 of 4 in the unionKt roadmap:

1. **Union ergonomics** (this spec) — constructor functions, `fold`, case accessors, generic
   unions, flattening.
2. kotlinx.serialization support for untagged unions.
3. `@Derive` extras on existing sealed hierarchies.
4. Intersection types, string literal unions, refined types.

Each sub-project gets its own spec → plan → implementation cycle.

## Goals

- Make generated unions pleasant to construct and consume without `when` + `is` boilerplate.
- Support generic unions such as `Either<L, R>`.
- Let a union include another union's cases directly (TypeScript-style `A | B`), including
  across Gradle modules.
- Keep everything that exists today source-compatible. The companion `onX` factories stay.

## Non-goals

- Opt-in flags for the new helpers. They are always generated.
- `xOrElse { }` accessors — `xOrNull ?: default` covers it.
- Flattening a *generic* union (not expressible with `KClass` literals; see §3).
- Constructor functions for type-parameter cases (see §2).

## Running example

```kotlin
data class User(val name: String)

@Union(Int::class, String::class, User::class)
interface ResultSpec
```

---

## 1. Helpers generated for every union

All helpers are emitted into the union's generated file (`Result.kt`) and take the union's
visibility (`public` or `internal`).

### 1.1 Constructor functions

One top-level function per **concrete** case, named exactly like the union:

```kotlin
fun Result(value: Int): Result = Result.OnInt(value)
fun Result(value: String): Result = Result.OnString(value)
fun Result(value: User): Result = Result.OnUser(value)
```

Kotlin's overload resolution picks the case; for related members (`CharSequence` and
`String`) the most specific overload wins, per the language's normal rules.

### 1.2 `fold`

```kotlin
inline fun <T> Result.fold(
    onInt: (Int) -> T,
    onString: (String) -> T,
    onUser: (User) -> T,
): T = when (this) {
    is Result.OnInt -> onInt(value)
    is Result.OnString -> onString(value)
    is Result.OnUser -> onUser(value)
}
```

- An extension, because an `inline` function with a body cannot be an interface member.
- Parameter names are `on<Case>`, matching the companion factory names (separate scopes, no
  clash).
- The result type parameter is `T`; if the union already has a type parameter named `T`,
  the first free name of `T1`, `T2`, … is used.

### 1.3 Case accessors

```kotlin
val Result.isInt: Boolean get() = this is Result.OnInt
val Result.intOrNull: Int? get() = (this as? Result.OnInt)?.value
// likewise isString / stringOrNull, isUser / userOrNull
```

- Extension properties, so they do not appear as members on every case class.
- The accessor stem is the case's simple name decapitalised Kotlin-style: a leading run of
  uppercase letters is lowercased, except for the last one when it is followed by a
  lowercase letter. `Int → int`, `DoubleArray → doubleArray`, `URL → url`,
  `URLParser → urlParser`, `L → l`.

---

## 2. Generic unions

### 2.1 Declaration

The marker's type parameters become cases:

```kotlin
@Union interface EitherSpec<L, R>                     // L | R
@Union(String::class) interface ParsedSpec<T>         // String | T
```

- `@Union` with no arguments is valid **only** when the marker declares type parameters. An
  empty `@Union` on a non-generic marker remains an error.
- Case order: the annotation's `types` first, in order, then type parameters in declaration
  order.
- KSP exposes these as `KSClassDeclaration.typeParameters`; a type parameter shadows any
  class of the same name, so there is no ambiguity.

### 2.2 Generated shape

```kotlin
sealed interface Either<out L, out R> {
    data class OnL<out L>(val value: L) : Either<L, Nothing>
    data class OnR<out R>(val value: R) : Either<Nothing, R>

    companion object {
        fun <L> onL(value: L): Either<L, Nothing> = OnL(value)
        fun <R> onR(value: R): Either<Nothing, R> = OnR(value)
    }
}

inline fun <L, R, T> Either<L, R>.fold(onL: (L) -> T, onR: (R) -> T): T
val <L, R> Either<L, R>.isL: Boolean
val <L, R> Either<L, R>.lOrNull: L?
// likewise isR / rOrNull
```

A concrete case in a generic union passes `Nothing` for every type parameter:

```kotlin
sealed interface Parsed<out T> {
    data class OnString(val value: String) : Parsed<Nothing>
    data class OnT<out T>(val value: T) : Parsed<T>
}
fun Parsed(value: String): Parsed<Nothing> = Parsed.OnString(value)
```

Because every parameter is `out`, `Either.onL("e")` is assignable to `Either<String, Int>`
without a cast.

### 2.3 Rules

- **Bounds are copied** to the union and to each type-parameter case
  (`<T : Number>` → `Either<out T : Number>`, `OnT<out T : Number>`). All bounds, including
  multiple bounds, are preserved. `Nothing` satisfies any bound.
- **Variance:** a marker parameter declared invariant or `out` is emitted as `out`. A
  parameter declared `in` is an error — a case stores a value of that type.
- **No constructor functions for type-parameter cases.** `fun <L> Either(value: L)` and
  `fun <R> Either(value: R)` have identical JVM signatures and would be ambiguous at every
  call site. Type-parameter cases are built with the companion factories. Concrete cases in a
  generic union still get constructor functions.
- **Nullable type arguments:** for `Either<String?, Int>`, `lOrNull` returns `null` both for
  "not an L" and for "an L holding null". `isL` distinguishes them. Documented, not worked
  around.
- **Name clashes** between a type parameter and a concrete member (a parameter named
  `String` beside `String::class`) are reported by the existing simple-name collision error.

---

## 3. Flattening

### 3.1 Trigger

A member type whose declaration carries `@Union` is flattened: its cases are inlined into
the outer union instead of becoming a single `On<Spec>` case.

```kotlin
@Union(Circle::class, Square::class) interface ShapeSpec
@Union(Int::class, ShapeSpec::class) interface ItemSpec
// Item = OnInt | OnCircle | OnSquare
```

The processor reads the nested marker's `@Union` arguments directly, so it never waits for
the nested union's generated file. Referencing the generated type (`Shape::class`) keeps
today's behaviour — a single `OnShape` case — so nesting remains available explicitly.

### 3.2 Cross-module flattening

`@Union` changes from `SOURCE` to `BINARY` retention. The annotation is then kept in class
files (still invisible to runtime reflection), so a Spec from a dependency is flattened
exactly like a local one.

Verified by spike on 2026-09-23 (KSP 2.3.12, KSP2): a `BINARY` annotation on a class from a
dependency classes dir was visible to a downstream processor with its `KClass` vararg
arguments intact, both via `getClassDeclarationByName` and via a `Spec::class` argument; a
`SOURCE` annotation was not visible at all.

Requirement: the dependency's Spec and generated union must be `public`. `internal` is not
visible across modules under Kotlin's rules, so this is not a unionKt constraint.

### 3.3 Order and merging

- Nested cases take the Spec's position in the list, in the nested union's own order.
- Members are **merged by exact type** (qualified name + type arguments); the first
  occurrence keeps its position. Types arriving through flattening merge silently.
- The same type listed twice **directly** in one `@Union` is merged with a **warning**
  (likely a typo).
- Two **different** types sharing a simple name remain an error; the message names the
  path each came through (`com.b.User via ShapeSpec`).

### 3.4 Transitive flattening and cycles

Flattening is resolved recursively: if `ItemSpec` contains `ShapeSpec` and `ShapeSpec`
contains `PolygonSpec`, `Item` receives every leaf type. A cycle is an error listing it:
`ASpec → BSpec → ASpec`.

### 3.5 Conversion functions

For every union flattened into the outer union — direct and transitive — one conversion is
generated in the outer union's file:

```kotlin
fun Shape.toItem(): Item = when (this) {
    is Shape.OnCircle -> Item.OnCircle(value)
    is Shape.OnSquare -> Item.OnSquare(value)
}
fun Polygon.toItem(): Item = …
```

- Name: `to<OuterUnion>`.
- Visibility: the stricter of the two unions (`internal` if either is `internal`).
- If the outer union is generic, the conversion returns `Item<Nothing, …>`, which covariance
  makes assignable to any `Item<A, B>`; the function needs no type parameters.

### 3.6 Limits

- **Flattening a generic Spec is an error.** A `KClass` literal cannot carry type arguments,
  so there is no way to say which `Either` is meant. Workaround: list the concrete types.
- **A `private` Spec can only be flattened from its own file.** Kotlin rejects
  `ShapeSpec::class` elsewhere before unionKt runs. Workaround: make the marker `internal`
  (the generated union is `internal` either way).

---

## 4. Diagnostics

### Errors

| Situation | Message (prefix) |
| --- | --- |
| `in` type parameter on marker | `@Union marker 'XSpec' type parameter 'T' is declared 'in', but a union case stores a T …` |
| Flattening a generic Spec | `@Union on 'ItemSpec' cannot flatten generic union 'EitherSpec' …` |
| Flattening cycle | `@Union on 'ASpec' has a flattening cycle: ASpec → BSpec → ASpec` |
| Simple-name clash via flattening | existing clash message, with `via <Spec>` on flattened members |

Removed: `declares type parameters, which the generated union cannot carry`.
Unchanged: every other existing error.

### Warnings

| Situation | Message (prefix) |
| --- | --- |
| Same type listed twice directly | `@Union on 'XSpec' lists 'kotlin.Int' more than once; the duplicates are merged.` |

---

## 5. Testing

All in `:processor-tests`, written test-first. Behavioural tests compile consuming code in
the same compilation rather than only matching generated text.

- **Helpers:** `Result(5)` / `Result("x")` resolve to the right case; `fold` works as an
  expression; accessors return the right values; an `internal` union produces `internal`
  helpers; the `T` → `T1` renaming when the union has a `T`; accessor stem casing
  (`DoubleArray`, `URL`).
- **Generics:** `Either<String, Int>` accepts `Either.onL("e")` and `Either.onR(1)` without
  casts; `fold` and accessors on a generic union; bounds are enforced (a violating type
  argument fails to compile); `in T` is an error; mixed concrete + generic union including
  its constructor function.
- **Flattening:** single-level; multi-level including the transitive conversion; overlapping
  nested unions merge; direct duplicate warns; clash via flattening errors with `via`;
  cycle errors; generic-Spec flattening errors; referencing the generated type nests;
  **two-module test** — compile a library containing a public Spec (with the processor), then
  compile a consumer that flattens it.
- **Existing suite** keeps passing; the "type parameters" error test is replaced by the
  generic tests.
- **`:sample`** gains an `Either` example and a flattened union so the real Gradle/KSP build
  exercises both.

## 6. Documentation

README gains sections for helpers, generic unions and flattening; the error table is
updated; the `@Union` KDoc and the "SOURCE retention leaves nothing in class files" note
change to describe `BINARY` retention.

## 7. Implementation notes

- `UnionProcessor.kt` is ~370 lines and would roughly double. Split it: the processor keeps
  validation and orchestration; member resolution (including flattening and cycle detection)
  and code emission (union, helpers, conversions) move to their own files in the same
  package.
- Member identity for merging uses the resolved KotlinPoet `TypeName` (which includes type
  arguments), not the simple name.
