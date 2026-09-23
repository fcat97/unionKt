package com.github.fcat97.unionkt

import kotlin.reflect.KClass

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
@Target(AnnotationTarget.CLASS)
// BINARY, not SOURCE: the annotation must survive into class files so a marker from a
// dependency can be flattened. It is still invisible to runtime reflection.
@Retention(AnnotationRetention.BINARY)
public annotation class Union(vararg val types: KClass<*>)
