package com.github.fcat97.unionkt.processor

import com.tschuchort.compiletesting.SourceFile
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `@Derive` on non-generic sealed types: shape, behaviour and visibility. */
class DeriveTest {

    private val uiState = SourceFile.kotlin(
        "UiState.kt",
        """
        package test

        import com.github.fcat97.unionkt.Derive

        @Derive
        sealed interface UiState {
            data object Loading : UiState
            data class Success(val items: List<String>) : UiState
            sealed interface Error : UiState {
                data object Offline : Error
                data class Server(val code: Int) : Error
            }
        }
        """.trimIndent(),
    )

    @Test
    fun `fold has one handler per direct child`() {
        compileWithUnionProcessor(
            uiState,
            SourceFile.kotlin(
                "Use.kt",
                """
                package test

                fun verify() {
                    val states = listOf(UiState.Loading, UiState.Success(listOf("a", "b")), UiState.Error.Offline, UiState.Error.Server(500))
                    val out = states.map { state ->
                        state.fold(onLoading = { "loading" }, onSuccess = { "items " + it.items.size }, onError = { "error" })
                    }
                    check(out == listOf("loading", "items 2", "error", "error")) { out.toString() }
                }
                """.trimIndent(),
            ),
        ).assertSucceeded().call("test.UseKt", "verify")
    }

    @Test
    fun `accessors report the active case and return it`() {
        compileWithUnionProcessor(
            uiState,
            SourceFile.kotlin(
                "Use.kt",
                """
                package test

                fun verify() {
                    val s: UiState = UiState.Success(listOf("a"))
                    check(s.isSuccess && !s.isLoading && !s.isError)
                    check(s.successOrNull?.items == listOf("a"))
                    check(s.errorOrNull == null)
                    val e: UiState = UiState.Error.Server(1)
                    check(e.errorOrNull == UiState.Error.Server(1))
                    check(UiState.Loading.isLoading)
                }
                """.trimIndent(),
            ),
        ).assertSucceeded().call("test.UseKt", "verify")
    }

    @Test
    fun `objects get no OrNull and the file is named after the target`() {
        val result = compileWithUnionProcessor(uiState).assertSucceeded()
        val generated = result.generated("UiStateDerived.kt")

        assertContains(generated, "@file:JvmName(\"UiStateDerivedKt\")")
        assertContains(generated, "public val UiState.isLoading: Boolean")
        assertFalse(generated.contains("loadingOrNull"), generated)
        assertContains(generated, "onLoading: () -> R")
        assertContains(generated, "onSuccess: (UiState.Success) -> R")
    }

    @Test
    fun `a nested sealed group can be derived too`() {
        compileWithUnionProcessor(
            SourceFile.kotlin(
                "Nested.kt",
                """
                package test

                import com.github.fcat97.unionkt.Derive

                @Derive
                sealed interface UiState {
                    data object Loading : UiState
                    @Derive
                    sealed interface Error : UiState {
                        data object Offline : Error
                        data class Server(val code: Int) : Error
                    }
                }

                fun verify() {
                    val state: UiState = UiState.Error.Server(503)
                    val text = state.fold(
                        onLoading = { "loading" },
                        onError = { error -> error.fold(onOffline = { "offline" }, onServer = { "server " + it.code }) },
                    )
                    check(text == "server 503") { text }
                }
                """.trimIndent(),
            ),
        ).assertSucceeded().also {
            assertTrue("UiStateErrorDerived.kt" in it.generatedFileNames(), it.generatedFileNames().toString())
        }.call("test.NestedKt", "verify")
    }

    @Test
    fun `cases declared in other files are found`() {
        compileWithUnionProcessor(
            SourceFile.kotlin("Shape.kt", "package test\n\n@com.github.fcat97.unionkt.Derive\nsealed interface Shape"),
            SourceFile.kotlin("Circle.kt", "package test\n\ndata class Circle(val radius: Int) : Shape"),
            SourceFile.kotlin(
                "Square.kt",
                """
                package test

                data class Square(val side: Int) : Shape

                fun verify() {
                    val shapes: List<Shape> = listOf(Circle(1), Square(2))
                    check(shapes.map { it.fold(onCircle = { c -> c.radius }, onSquare = { s -> s.side * 10 }) } == listOf(1, 20))
                }
                """.trimIndent(),
            ),
        ).assertSucceeded().call("test.SquareKt", "verify")
    }

    @Test
    fun `a sealed class and a nested target work`() {
        compileWithUnionProcessor(
            SourceFile.kotlin(
                "Ops.kt",
                """
                package test

                import com.github.fcat97.unionkt.Derive

                @Derive
                sealed class Op {
                    class Add(val n: Int) : Op()
                    object Reset : Op()
                }

                class Screen {
                    @Derive
                    sealed interface State {
                        data object Idle : State
                        data class Busy(val progress: Int) : State
                    }
                }

                fun verify() {
                    check(Op.Add(2).fold(onAdd = { it.n }, onReset = { 0 }) == 2)
                    check(Op.Reset.isReset)
                    val state: Screen.State = Screen.State.Busy(40)
                    check(state.busyOrNull?.progress == 40)
                }
                """.trimIndent(),
            ),
        ).assertSucceeded().also {
            assertTrue("ScreenStateDerived.kt" in it.generatedFileNames(), it.generatedFileNames().toString())
        }.call("test.OpsKt", "verify")
    }

    @Test
    fun `accessor names are decapitalised Kotlin style`() {
        compileWithUnionProcessor(
            SourceFile.kotlin(
                "Net.kt",
                """
                package test

                import com.github.fcat97.unionkt.Derive

                @Derive
                sealed interface Net {
                    data class URLError(val url: String) : Net
                    data object IOTimeout : Net
                }

                fun verify() {
                    val n: Net = Net.URLError("x")
                    check(n.urlErrorOrNull?.url == "x")
                    check(!n.isIOTimeout && n.isURLError)
                }
                """.trimIndent(),
            ),
        ).assertSucceeded().call("test.NetKt", "verify")
    }

    @Test
    fun `helpers are no more visible than what they mention`() {
        val result = compileWithUnionProcessor(
            SourceFile.kotlin(
                "Vis.kt",
                """
                package test

                import com.github.fcat97.unionkt.Derive

                @Derive
                sealed interface Mixed {
                    data class Open(val x: Int) : Mixed
                }

                // Top level: a class nested in an interface cannot be internal.
                internal data class Hidden(val y: Int) : Mixed

                @Derive
                internal sealed interface Secret {
                    data object A : Secret
                }

                internal class Holder {
                    @Derive
                    sealed interface Inner {
                        data object X : Inner
                    }
                }
                """.trimIndent(),
            ),
        ).assertSucceeded()

        val mixed = result.generated("MixedDerived.kt")
        assertContains(mixed, "internal inline fun <R> Mixed.fold(")
        assertContains(mixed, "public val Mixed.isOpen: Boolean")
        assertContains(mixed, "internal val Mixed.isHidden: Boolean")
        assertContains(result.generated("SecretDerived.kt"), "internal inline fun <R> Secret.fold(")
        assertContains(result.generated("HolderInnerDerived.kt"), "internal inline fun <R> Holder.Inner.fold(")
    }
}
