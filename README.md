# unionKt

[![Maven Central](https://img.shields.io/maven-central/v/io.github.fcat97.unionkt/processor)](https://central.sonatype.com/artifact/io.github.fcat97.unionkt/processor)

Kotlin doesn't have union types like TypeScript's `Int | String`. unionKt gives you one.

Tell it which types a value can be, and it writes a type-safe union for you. Kotlin then
makes sure you handle every case: forget one and your code won't compile.

Works everywhere Kotlin does: Android, JVM, iOS, macOS, web (JS and Wasm), Linux and Windows.

```kotlin
@Union(Int::class, String::class, User::class)
interface ResultSpec

val result = Result(5)

val text = when (result) {
    is Result.OnInt    -> "number ${result.value}"
    is Result.OnString -> "text ${result.value}"
    is Result.OnUser   -> "user ${result.value.name}"
}
```

## Setup

unionKt is on Maven Central. In your module's `build.gradle.kts`:

```kotlin
plugins {
    id("com.google.devtools.ksp") version "2.3.12"
}

dependencies {
    implementation("io.github.fcat97.unionkt:annotations:0.5.0")
    ksp("io.github.fcat97.unionkt:processor:0.5.0")
}
```

Tested with Kotlin 2.4.20 and KSP 2.3.12. On Android with AGP 9, use KSP 2.3.4 or newer.

### Kotlin Multiplatform

Add the annotations to `commonMain`, and the processor to each target's KSP configuration:

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("io.github.fcat97.unionkt:annotations:0.5.0")
        }
    }
}

dependencies {
    listOf("kspJvm", "kspJs", "kspIosArm64", "kspIosSimulatorArm64").forEach {
        add(it, "io.github.fcat97.unionkt:processor:0.5.0")
    }
}
```

Use the names that match your targets (`kspAndroid`, `kspWasmJs`, `kspLinuxX64`, …).

## Making a union

Write an interface whose name ends in `Spec` and list your types. unionKt creates the union
with the same name, minus `Spec`:

```kotlin
@Union(Int::class, String::class, User::class)
interface ResultSpec          // you get: Result
```

Each type becomes a case called `On<Type>`, and the value inside it is `value`.

## Using a union

**Create one.** Just call the union's name with a value:

```kotlin
val a = Result(5)
val b = Result("hello")
val c = Result(User("Ada"))
```

**Handle every case** with `when`, or with `fold` if you prefer one expression:

```kotlin
val text = result.fold(
    onInt = { "number $it" },
    onString = { "text $it" },
    onUser = { "user ${it.name}" },
)
```

**Check or pull out one case:**

```kotlin
result.isInt        // true or false
result.intOrNull    // the Int, or null if it's something else
```

## Generic unions

Use type parameters when the types should be picked later. The classic example is
"success or error":

```kotlin
@Union
interface EitherSpec<L, R>

fun parseAge(text: String): Either<String, Int> =
    text.toIntOrNull()?.let { Either.onR(it) } ?: Either.onL("not a number")

parseAge("42").fold(onL = { "error: $it" }, onR = { "age $it" })
```

You can mix fixed types and type parameters too: `@Union(String::class) interface ParsedSpec<T>`.

## Combining unions

Put one union's `Spec` inside another and its types are added directly:

```kotlin
@Union(Circle::class, Square::class)
interface ShapeSpec

@Union(Int::class, ShapeSpec::class)
interface ItemSpec            // Item can be an Int, a Circle or a Square

val item: Item = shape.toItem()   // turn a Shape into an Item
```

If you want the whole union as one case instead, use the union itself: `Shape::class`.

## Helpers for your own sealed classes

Already have a sealed class or interface? Add `@Derive` and you get `fold` and the same
helpers, without writing a `Spec`:

```kotlin
@Derive
sealed interface UiState {
    data object Loading : UiState
    data class Success(val items: List<Item>) : UiState
    data class Error(val message: String) : UiState
}

val text = state.fold(
    onLoading = { "Loading…" },
    onSuccess = { "${it.items.size} items" },
    onError = { it.message },
)

state.isLoading
state.successOrNull?.items
```

It works with generic sealed types like `Result<T>` too. If a case is itself a sealed type,
`fold` treats it as one case. Add `@Derive` to it as well to get its own `fold`.

## JSON with kotlinx.serialization

Want to read and write unions as JSON? Add one more line, next to the usual
kotlinx.serialization setup:

```kotlin
plugins {
    kotlin("plugin.serialization") version "2.4.20"
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    ksp("io.github.fcat97.unionkt:serialization-kotlinx:0.5.0")
}
```

Now every union works with `Json`, with no extra wrapper in the JSON:

```kotlin
Json.encodeToString<Result>(Result(5))          // 5
Json.encodeToString<Result>(Result("hi"))       // "hi"
Json.decodeFromString<Result>("\"hi\"")          // Result.OnString("hi")
```

Unions also work as properties inside your own `@Serializable` classes.

When reading JSON, unionKt tries your types in the order you listed them and uses the first
one that fits. So if two of your classes look alike, list the more specific one first.

Every type in a serializable union must be `@Serializable` (basic Kotlin types and enums
already are). You need kotlinx-serialization-json 1.6.3 or newer. In a multiplatform project,
add `serialization-kotlinx` to the same KSP configurations as the processor.

## License

[MIT](LICENSE)
