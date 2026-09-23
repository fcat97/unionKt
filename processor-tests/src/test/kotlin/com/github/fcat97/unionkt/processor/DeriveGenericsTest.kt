package com.github.fcat97.unionkt.processor

import com.tschuchort.compiletesting.SourceFile
import kotlin.test.Test
import kotlin.test.assertContains

/** `@Derive` on generic sealed types (spec §3). */
class DeriveGenericsTest {

    @Test
    fun `a generic sealed type maps its cases onto its parameters`() {
        val result = compileWithUnionProcessor(
            SourceFile.kotlin(
                "Result.kt",
                """
                package test

                import com.github.fcat97.unionkt.Derive

                @Derive
                sealed interface Result<out T> {
                    data class Ok<out T>(val value: T) : Result<T>
                    data class Err(val error: String) : Result<Nothing>
                    data object Pending : Result<Nothing>
                }

                fun verify() {
                    val r: Result<Int> = Result.Ok(41)
                    check(r.fold(onOk = { it.value + 1 }, onErr = { -1 }, onPending = { 0 }) == 42)
                    check(r.okOrNull?.value == 41)
                    check(r.isOk && !r.isErr && !r.isPending)
                    val e: Result<Int> = Result.Err("x")
                    check(e.errOrNull?.error == "x")
                }
                """.trimIndent(),
            ),
        ).assertSucceeded()

        val generated = result.generated("ResultDerived.kt")
        assertContains(generated, "public inline fun <T, R> Result<T>.fold(")
        assertContains(generated, "onOk: (Result.Ok<T>) -> R")
        assertContains(generated, "public val <T> Result<T>.okOrNull: Result.Ok<T>?")
        result.call("test.ResultKt", "verify")
    }

    @Test
    fun `unmappable parameters become star projections`() {
        val result = compileWithUnionProcessor(
            SourceFile.kotlin(
                "Box.kt",
                """
                package test

                import com.github.fcat97.unionkt.Derive

                @Derive
                sealed interface Box<out T> {
                    data class Tagged<out T, out Tag>(val value: T, val tag: Tag) : Box<T>
                    data class Many<out E>(val items: List<E>) : Box<List<E>>
                }

                fun verify() {
                    val b: Box<Int> = Box.Tagged(1, "t")
                    check(b.fold(onTagged = { it.value + 1 }, onMany = { -1 }) == 2)
                    check(b.taggedOrNull?.tag == "t")
                    val m: Box<List<String>> = Box.Many(listOf("a"))
                    check(m.fold(onTagged = { 0 }, onMany = { it.items.size }) == 1)
                    check(m.manyOrNull?.items == listOf("a"))
                }
                """.trimIndent(),
            ),
        ).assertSucceeded()

        val generated = result.generated("BoxDerived.kt")
        assertContains(generated, "onTagged: (Box.Tagged<T, *>) -> R")
        assertContains(generated, "onMany: (Box.Many<*>) -> R")
        result.call("test.BoxKt", "verify")
    }

    @Test
    fun `bounds carry over to the helpers`() {
        val generated = compileWithUnionProcessor(
            SourceFile.kotlin(
                "Num.kt",
                """
                package test

                import com.github.fcat97.unionkt.Derive

                @Derive
                sealed interface Num<out T : Number> {
                    data class One<out T : Number>(val value: T) : Num<T>
                }

                fun doubled(n: Num<Int>): Int = n.fold(onOne = { it.value * 2 })
                """.trimIndent(),
            ),
        ).assertSucceeded().generated("NumDerived.kt")

        assertContains(generated, "public inline fun <T : Number, R> Num<T>.fold(")
    }

    @Test
    fun `fold renames its result type when R is taken`() {
        val result = compileWithUnionProcessor(
            SourceFile.kotlin(
                "Pair2.kt",
                """
                package test

                import com.github.fcat97.unionkt.Derive

                @Derive
                sealed interface Pair2<out L, out R> {
                    data class Left<out L>(val l: L) : Pair2<L, Nothing>
                    data class Right<out R>(val r: R) : Pair2<Nothing, R>
                }

                fun verify() {
                    val p: Pair2<String, Int> = Pair2.Right(3)
                    check(p.fold(onLeft = { it.l.length }, onRight = { it.r * 2 }) == 6)
                }
                """.trimIndent(),
            ),
        ).assertSucceeded()

        assertContains(result.generated("Pair2Derived.kt"), "fun <L, R, R1> Pair2<L, R>.fold(")
        result.call("test.Pair2Kt", "verify")
    }
}
