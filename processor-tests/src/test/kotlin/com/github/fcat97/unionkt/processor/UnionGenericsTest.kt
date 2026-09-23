package com.github.fcat97.unionkt.processor

import com.tschuchort.compiletesting.SourceFile
import kotlin.test.Test
import kotlin.test.assertContains

/** Generic unions: the marker's type parameters become cases. */
class UnionGenericsTest {

    private val either = SourceFile.kotlin(
        "Either.kt",
        """
        package test

        import com.github.fcat97.unionkt.Union

        @Union
        interface EitherSpec<L, R>
        """.trimIndent(),
    )

    @Test
    fun `each type parameter becomes a covariant case`() {
        val generated = compileWithUnionProcessor(either).assertSucceeded().generated("Either.kt")

        assertContains(generated, "public sealed interface Either<out L, out R>")
        assertContains(generated, "public data class OnL<out L>(")
        assertContains(generated, "Either<L, Nothing>")
        assertContains(generated, "public data class OnR<out R>(")
        assertContains(generated, "Either<Nothing, R>")
        assertContains(generated, "public fun <L> onL(`value`: L): Either<L, Nothing> = OnL(`value`)")
    }

    @Test
    fun `cases are assignable to any parameterisation without casts`() {
        compileWithUnionProcessor(
            either,
            SourceFile.kotlin(
                "Use.kt",
                """
                package test

                fun left(): Either<String, Int> = Either.onL("e")
                fun right(): Either<String, Int> = Either.OnR(1)

                fun verify() {
                    val described = listOf(left(), right()).map { e ->
                        when (e) {
                            is Either.OnL -> "left " + e.value.length
                            is Either.OnR -> "right " + (e.value + 1)
                        }
                    }
                    check(described == listOf("left 1", "right 2")) { described.toString() }
                }
                """.trimIndent(),
            ),
        ).assertSucceeded().call("test.UseKt", "verify")
    }

    @Test
    fun `bounds carry over to the union`() {
        val result = compileWithUnionProcessor(
            SourceFile.kotlin(
                "Num.kt",
                """
                package test

                import com.github.fcat97.unionkt.Union

                @Union
                interface NumSpec<T : Number>

                fun ok(): Num<Int> = Num.onT(1)
                """.trimIndent(),
            ),
        ).assertSucceeded()

        assertContains(result.generated("Num.kt"), "public sealed interface Num<out T : Number>")
    }

    @Test
    fun `a type argument outside the bound fails to compile`() {
        // If the compiler words this differently, use the fragment from the actual output;
        // what matters is that the failure is the bound violation.
        compileWithUnionProcessor(
            SourceFile.kotlin(
                "NumBad.kt",
                """
                package test

                import com.github.fcat97.unionkt.Union

                @Union
                interface NumSpec<T : Number>

                fun bad(): Num<String> = Num.onT("x")
                """.trimIndent(),
            ),
        ).assertFailedWith("not within its bounds")
    }

    @Test
    fun `a mixed concrete and generic union works`() {
        compileWithUnionProcessor(
            SourceFile.kotlin(
                "Parsed.kt",
                """
                package test

                import com.github.fcat97.unionkt.Union

                @Union(String::class)
                interface ParsedSpec<T>

                fun raw(): Parsed<Int> = Parsed.onString("oops")
                fun parsed(): Parsed<Int> = Parsed.onT(41)

                fun verify() {
                    val described = listOf(raw(), parsed()).map { p ->
                        when (p) {
                            is Parsed.OnString -> "raw " + p.value.length
                            is Parsed.OnT -> "parsed " + (p.value + 1)
                        }
                    }
                    check(described == listOf("raw 4", "parsed 42")) { described.toString() }
                }
                """.trimIndent(),
            ),
        ).assertSucceeded().call("test.ParsedKt", "verify")
    }

    @Test
    fun `an in type parameter is rejected`() {
        compileWithUnionProcessor(
            SourceFile.kotlin(
                "Sink.kt",
                """
                package test

                import com.github.fcat97.unionkt.Union

                @Union
                interface SinkSpec<in T>
                """.trimIndent(),
            ),
        ).assertFailedWith(
            "@Union marker 'SinkSpec' type parameter 'T' is declared 'in'",
            "Remove 'in'",
        )
    }

    @Test
    fun `a bound referring to another type parameter is rejected`() {
        // Each case class declares only its own type parameter, so `OnU<out U : T>` would
        // refer to a T that does not exist there.
        compileWithUnionProcessor(
            SourceFile.kotlin(
                "Pair.kt",
                """
                package test

                import com.github.fcat97.unionkt.Union

                @Union
                interface PairSpec<T, U : T>
                """.trimIndent(),
            ),
        ).assertFailedWith("@Union marker 'PairSpec' type parameter 'U' has a bound that refers to 'T'")
    }

    @Test
    fun `a type parameter clashing with a member's simple name is rejected`() {
        compileWithUnionProcessor(
            SourceFile.kotlin(
                "Weird.kt",
                """
                package test

                import com.github.fcat97.unionkt.Union

                @Union(kotlin.String::class)
                interface WeirdSpec<String>
                """.trimIndent(),
            ),
        ).assertFailedWith("2 member types whose simple name is 'String'")
    }
}
