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
