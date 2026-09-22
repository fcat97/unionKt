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
 * ```
 *
 * Requirements, all of which are enforced with a compile error rather than a
 * silent fallback:
 *
 * - the annotated declaration must be an `interface`;
 * - its simple name must end in `Spec` and be longer than `Spec` itself;
 * - it must live in a named (non-default) package;
 * - [types] must be non-empty, and no two members may share a simple name.
 *
 * The generated union mirrors the marker's visibility, except that a `private`
 * marker yields an `internal` union — a `private` top-level declaration in the
 * generated file would be invisible to the file that declared the marker.
 *
 * @param types the member types of the union, in declaration order.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
public annotation class Union(vararg val types: KClass<*>)
