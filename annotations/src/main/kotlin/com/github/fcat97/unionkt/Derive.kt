package com.github.fcat97.unionkt

/**
 * Generates `fold` and case accessors for a sealed class or interface you wrote.
 *
 * ```
 * @Derive
 * sealed interface UiState {
 *     data object Loading : UiState
 *     data class Success(val items: List<Item>) : UiState
 * }
 *
 * // generated, in UiStateDerived.kt:
 * //   inline fun <R> UiState.fold(onLoading: () -> R, onSuccess: (UiState.Success) -> R): R
 * //   val UiState.isLoading: Boolean
 * //   val UiState.isSuccess: Boolean
 * //   val UiState.successOrNull: UiState.Success?
 * ```
 *
 * The cases are the **direct** subclasses: a subclass that is itself sealed is one case (annotate
 * it too for its own helpers). An `object` case gets a no-argument handler and no `xOrNull`.
 * Generic sealed types are supported. No helper is more visible than the classes it mentions.
 *
 * Enforced with compile errors: the target must be a sealed class or interface in a named package,
 * visible to other files (not private or protected), with at least one subclass; every subclass
 * must be visible to other files, and no two may share a simple name.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
public annotation class Derive
