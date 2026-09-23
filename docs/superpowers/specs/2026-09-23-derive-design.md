# `@Derive` on existing sealed hierarchies — design

Sub-project 3 of 4 in the unionKt roadmap:

1. Union ergonomics — shipped in 0.2.0.
2. Extension API and kotlinx.serialization — shipped in 0.3.0.
3. **`@Derive`** (this spec) — `fold` and case accessors for sealed types the user wrote.
4. Intersection types, string literal unions, refined types.

## Goals

- Give hand-written sealed classes and interfaces the helpers generated unions have (`fold`,
  `isX`, `xOrNull`) with one annotation and no `Spec` convention.
- Support generic sealed types such as `Result<out T>`.
- Ship in the existing `annotations` and `processor` artifacts.

## Non-goals

- Options on `@Derive`; every helper is always generated.
- Handlers for the leaves of nested sealed groups (`fold` covers direct children only, §2.2).
- Serialization: kotlinx.serialization already supports sealed classes natively, and KSP cannot
  add `@Serializable(with = …)` to a user-written class.
- Running `UnionExtension`s for `@Derive` types: the extension API describes unions, and an
  extension could not annotate a user-written class either.
- Constructor functions: the user's classes already have constructors.

## 1. The annotation

In `:annotations`, next to `@Union`:

```kotlin
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
public annotation class Derive
```

`BINARY` for consistency with `@Union`; nothing reads it across modules today.

## 2. Generated code

### 2.1 Running example

```kotlin
@Derive
sealed interface UiState {
    data object Loading : UiState
    data class Success(val items: List<Item>) : UiState
    sealed interface Error : UiState {
        data object Offline : Error
        data class Server(val code: Int) : Error
    }
}
```

generates `UiStateDerived.kt` in the sealed type's package:

```kotlin
@file:JvmName("UiStateDerivedKt")

inline fun <R> UiState.fold(
    onLoading: () -> R,
    onSuccess: (UiState.Success) -> R,
    onError: (UiState.Error) -> R,
): R = when (this) {
    is UiState.Loading -> onLoading()
    is UiState.Success -> onSuccess(this)
    is UiState.Error -> onError(this)
}

val UiState.isLoading: Boolean get() = this is UiState.Loading
val UiState.isSuccess: Boolean get() = this is UiState.Success
val UiState.successOrNull: UiState.Success? get() = if (this is UiState.Success) this else null
val UiState.isError: Boolean get() = this is UiState.Error
val UiState.errorOrNull: UiState.Error? get() = if (this is UiState.Error) this else null
```

### 2.2 Cases

The cases are the sealed type's **direct** subclasses (`getSealedSubclasses()`), in the order
KSP returns them. A direct subclass that is itself sealed is one case; its own subclasses are
not expanded. Putting `@Derive` on it as well gives it its own helpers.

- An `object` case: the handler takes no argument (`onLoading: () -> R`); it gets `isX` but no
  `xOrNull`.
- Any other case: the handler receives the instance (`onSuccess: (UiState.Success) -> R`); it gets
  `isX` and `xOrNull`, which returns the instance.

### 2.3 Names

`on<Name>`, `is<Name>` and `<stem>OrNull`, where `<Name>` is the subclass's simple name and
`<stem>` is `accessorStem(<Name>)` (shared with unions: `URLError → urlError`). The result type
parameter of `fold` is `R`, or the first free name of `R1`, `R2`, … if the sealed type already
declares `R`.

The file is `<SimpleName>Derived.kt` and carries `@file:JvmName("<SimpleName>DerivedKt")`, so its
facade class cannot clash with a user file named `<SimpleName>Derived.kt` in the same package.
For a nested sealed type (`Outer.State`) the file and facade use the joined simple names
(`OuterStateDerived.kt`).

### 2.4 Visibility

A helper cannot be more visible than any declaration it mentions:

- `fold`: the strictest of the sealed type's visibility and every case's visibility.
- A case's `isX` / `xOrNull`: the stricter of the sealed type's visibility and that case's.

`public` stays `public` and `internal` stays `internal`. A sealed type or case that is not visible
to other files — `private` or `protected`, itself or through a containing class — is an error
(§5): unlike a union marker, which the generated union never mentions, the helpers *reference* the
sealed type and its cases, so they cannot live in a separate file.

Visibility is the effective one: a class nested in an `internal` class is treated as `internal`.

## 3. Generic sealed types

```kotlin
@Derive
sealed interface Result<out T> {
    data class Ok<out T>(val value: T) : Result<T>
    data class Err(val error: Throwable) : Result<Nothing>
    data object Pending : Result<Nothing>
}
```

generates

```kotlin
inline fun <T, R> Result<T>.fold(
    onOk: (Result.Ok<T>) -> R,
    onErr: (Result.Err) -> R,
    onPending: () -> R,
): R
val <T> Result<T>.isOk: Boolean
val <T> Result<T>.okOrNull: Result.Ok<T>?
// …
```

Helpers declare the sealed type's type parameters (variance removed, bounds kept) and use
`Result<T, …>` as the receiver.

### 3.1 A case's type

For each case, find its supertype reference to the sealed type (`Ok<A> : Result<A>`) and build
the case's type in terms of the sealed type's parameters:

- For each of the case's own type parameters `P`, in order: if some position `i` of the supertype
  reference is exactly the type variable `P`, use the sealed type's `i`-th type parameter;
  otherwise use `*`.
- A non-generic case is used as is.

| Case declaration | Type in helpers |
| --- | --- |
| `Ok<A> : Result<A>` | `Ok<T>` |
| `Err : Result<Nothing>` | `Err` |
| `Pair<A, B> : Result<A>` | `Pair<T, *>` |
| `Many<A> : Result<List<A>>` | `Many<*>` |

Case checks in generated code:

- Every case type argument mapped (`Ok<T>`), or no type parameters: a bare `is Result.Ok`, whose
  type arguments Kotlin infers from the subject — the pattern the union helpers already use.
- Every argument `*` (`Many<*>`): `is Box.Many<*>`; the smart-cast type is the case type.
- Mixed (`Tagged<T, *>`): Kotlin cannot infer the `*` part, so the check is `is Box.Tagged<*, *>`
  and the value is cast to `Box.Tagged<T, *>`. The cast is sound — the case's supertype
  declaration fixes how its parameters relate to the sealed type's — and the unchecked-cast
  warning is suppressed on exactly the helpers that contain one.

### 3.2 Bounds

The sealed type's bounds are copied to every helper's type parameters
(`sealed interface Num<T : Number>` → `fun <T : Number, R> Num<T>.fold(…)`).

## 4. Architecture

In `:processor`; no new modules.

| File | Responsibility |
| --- | --- |
| `DeriveProcessor.kt` | KSP processor for `@Derive`: validation, cases, case types, visibility. |
| `DeriveProcessorProvider.kt` | Its provider, registered in the existing `META-INF/services/…SymbolProcessorProvider` file next to `UnionProcessorProvider`. It opts in to KSP's new features the same way (reflectively). |
| `DeriveModel.kt` | Plain data passed to the writer. |
| `DeriveWriter.kt` | Emits `<SimpleName>Derived.kt`. |
| `Naming.kt` | Shared, unchanged: `accessorStem`, `freeTypeVariableName` (gains a `base` parameter so `fold` can ask for `R`, `R1`, …). |

**Incremental processing:** the output depends on the sealed type's file and every case's file,
and is written with `Dependencies(aggregating = true, …)`, so KSP re-runs it when a new file
(possibly containing a new case) appears.

The test harness registers both providers.

## 5. Errors

| Situation | Message (prefix) |
| --- | --- |
| Target is not a sealed class or interface | `@Derive may only be applied to a sealed class or interface, but 'X' is …` |
| Target in the default package | `@Derive target 'X' must live in a named package …` |
| Target not visible to other files | `@Derive target 'X' is not visible to other files (it, or a class containing it, is private or protected). Make it internal or public.` |
| Two targets generating the same file (`Outer.State` and `OuterState`) | `@Derive on 'State' would generate 'pkg.OuterStateDerived.kt', which another @Derive target already generates. Rename one of them.` |
| No subclasses | `@Derive on 'X' found no subclasses. A sealed type needs at least one to derive helpers for.` |
| A case not visible to other files | `@Derive on 'X' cannot reference subclass 'pkg.X.Y': it, or a class containing it, is private or protected, so the generated file cannot see it. Make it internal or public.` |
| Two cases with the same simple name | `@Derive on 'X' has 2 subclasses whose simple name is 'Item' (…), which would generate clashing 'onItem' handlers. Rename one of them.` |

## 6. Testing

In `:processor-tests`, compiling and running consumer code:

- **Basics:** `fold` over object, class and nested sealed-group cases; `isX` / `xOrNull`; no
  `xOrNull` for objects; a case declared in another file; accessor casing; the file name and
  `@file:JvmName`; a sealed *class* as well as a sealed interface; a nested sealed target.
- **Visibility:** an `internal` case makes `fold` and that case's accessors `internal`; an
  `internal` sealed type; a sealed type nested in an `internal` class.
- **Generics:** `Result<out T>` with `Ok`, `Err : Result<Nothing>` and an object case; a case with
  a free extra parameter (`Tagged<T, *>`, checked with `<*, *>` and cast); a wrapped parameter
  (`Many<*>`); bounds; `R` renamed to `R1`.
- **Errors:** every row of §5.
- The existing suites stay green.

`:sample` gains a `@Derive` `UiState`.

## 7. Documentation

README gains a `@Derive` section (example, direct-children rule, objects, generics, visibility)
and the §5 rows in the error table. Release: **0.4.0**.
