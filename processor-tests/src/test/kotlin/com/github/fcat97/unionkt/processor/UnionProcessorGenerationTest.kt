package com.github.fcat97.unionkt.processor

import com.tschuchort.compiletesting.SourceFile
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

/** The shape of the generated code, and the visibility rules around it. */
class UnionProcessorGenerationTest {

    private val user = SourceFile.kotlin(
        "User.kt",
        """
        package test

        data class User(val name: String)
        """.trimIndent(),
    )

    @Test
    fun `generates a sealed interface with a case and factory per member`() {
        val result = compileWithUnionProcessor(
            user,
            SourceFile.kotlin(
                "Result.kt",
                """
                package test

                import com.github.fcat97.unionkt.Union

                @Union(Int::class, String::class, User::class)
                interface ResultSpec
                """.trimIndent(),
            ),
        ).assertSucceeded()

        val generated = result.generated("Result.kt")

        assertContains(generated, "public sealed interface Result")
        assertContains(generated, "public data class OnInt(")
        assertContains(generated, "public data class OnString(")
        assertContains(generated, "public data class OnUser(")
        assertContains(generated, "public fun onInt(`value`: Int): Result = OnInt(`value`)")
        assertContains(generated, "public fun onString(`value`: String): Result = OnString(`value`)")
        assertContains(generated, "public fun onUser(`value`: User): Result = OnUser(`value`)")
        assertContains(generated, "public companion object")
    }

    @Test
    fun `generates into the marker's package`() {
        val result = compileWithUnionProcessor(
            SourceFile.kotlin(
                "Deep.kt",
                """
                package com.example.deeply.nested

                import com.github.fcat97.unionkt.Union

                @Union(Int::class)
                interface DeepSpec
                """.trimIndent(),
            ),
        ).assertSucceeded()

        assertContains(result.generated("Deep.kt"), "package com.example.deeply.nested")
    }

    @Test
    fun `the generated union compiles and its when is exhaustive`() {
        // The real guarantee: consuming code in the same compilation type-checks against
        // the generated sealed interface without an else branch.
        compileWithUnionProcessor(
            user,
            SourceFile.kotlin(
                "Result.kt",
                """
                package test

                import com.github.fcat97.unionkt.Union

                @Union(Int::class, String::class, User::class)
                interface ResultSpec

                fun describe(result: Result): String = when (result) {
                    is Result.OnInt -> "int=" + result.value
                    is Result.OnString -> "string=" + result.value
                    is Result.OnUser -> "user=" + result.value.name
                }

                fun build(): Result = Result.onInt(5)
                """.trimIndent(),
            ),
        ).assertSucceeded()
    }

    @Test
    fun `a missing when branch fails to compile`() {
        compileWithUnionProcessor(
            SourceFile.kotlin(
                "Partial.kt",
                """
                package test

                import com.github.fcat97.unionkt.Union

                @Union(Int::class, String::class)
                interface PartialSpec

                fun describe(p: Partial): String = when (p) {
                    is Partial.OnInt -> "int"
                }
                """.trimIndent(),
            ),
        ).assertFailedWith("'when' expression must be exhaustive")
    }

    @Test
    fun `a public marker produces a public union`() {
        val result = compileWithUnionProcessor(
            SourceFile.kotlin(
                "Pub.kt",
                """
                package test

                import com.github.fcat97.unionkt.Union

                @Union(Int::class)
                interface PubSpec
                """.trimIndent(),
            ),
        ).assertSucceeded()

        assertContains(result.generated("Pub.kt"), "public sealed interface Pub")
        result.assertDoesNotWarn("is private")
    }

    @Test
    fun `an internal marker produces an internal union without warning`() {
        val result = compileWithUnionProcessor(
            SourceFile.kotlin(
                "Internal.kt",
                """
                package test

                import com.github.fcat97.unionkt.Union

                @Union(Int::class)
                internal interface InternalSpec
                """.trimIndent(),
            ),
        ).assertSucceeded()

        assertContains(result.generated("Internal.kt"), "internal sealed interface Internal")
        result.assertDoesNotWarn("is private")
    }

    @Test
    fun `a private marker produces an internal union and warns why`() {
        val result = compileWithUnionProcessor(
            SourceFile.kotlin(
                "Priv.kt",
                """
                package test

                import com.github.fcat97.unionkt.Union

                @Union(Int::class)
                private interface PrivSpec
                """.trimIndent(),
            ),
        ).assertSucceeded()

        assertContains(result.generated("Priv.kt"), "internal sealed interface Priv")
        result.assertWarns("@Union marker 'PrivSpec' is private")
        result.assertWarns("would be invisible to 'Priv.kt'")
    }

    @Test
    fun `a generic member type is star-projected`() {
        // A KClass literal cannot carry type arguments, so `List::class` arrives with its
        // parameter unresolved. Emitting `List<T>` would not compile.
        val result = compileWithUnionProcessor(
            SourceFile.kotlin(
                "Generic.kt",
                """
                package test

                import com.github.fcat97.unionkt.Union

                @Union(List::class, Int::class)
                interface GenericSpec
                """.trimIndent(),
            ),
        ).assertSucceeded()

        assertContains(result.generated("Generic.kt"), "public data class OnList(")
        assertContains(result.generated("Generic.kt"), "List<*>")
    }

    @Test
    fun `a nested member type uses its simple name for the case`() {
        val result = compileWithUnionProcessor(
            SourceFile.kotlin(
                "Nested.kt",
                """
                package test

                import com.github.fcat97.unionkt.Union

                class Outer {
                    class Inner
                }

                @Union(Outer.Inner::class, Int::class)
                interface NestedSpec
                """.trimIndent(),
            ),
        ).assertSucceeded()

        val generated = result.generated("Nested.kt")
        assertContains(generated, "public data class OnInner(")
        assertContains(generated, "Outer.Inner")
    }

    @Test
    fun `several markers in one compilation each get their own file`() {
        val result = compileWithUnionProcessor(
            SourceFile.kotlin(
                "Many.kt",
                """
                package test

                import com.github.fcat97.unionkt.Union

                @Union(Int::class)
                interface AlphaSpec

                @Union(String::class)
                interface BetaSpec
                """.trimIndent(),
            ),
        ).assertSucceeded()

        assertEquals(listOf("Alpha.kt", "Beta.kt"), result.generatedFileNames())
    }

    @Test
    fun `the processor is opted in to KSP's upcoming features`() {
        // Without registerProcessorForNewFeatures, KSP emits a forward-compatibility
        // notice into every consuming build.
        compileWithUnionProcessor(
            SourceFile.kotlin(
                "OptIn.kt",
                """
                package test

                import com.github.fcat97.unionkt.Union

                @Union(Int::class)
                interface OptInSpec
                """.trimIndent(),
            ),
        ).assertSucceeded().assertDoesNotWarn("has not opted in for upcoming features")
    }

    @Test
    fun `a single member union is valid`() {
        val result = compileWithUnionProcessor(
            SourceFile.kotlin(
                "One.kt",
                """
                package test

                import com.github.fcat97.unionkt.Union

                @Union(Int::class)
                interface OneSpec
                """.trimIndent(),
            ),
        ).assertSucceeded()

        assertContains(result.generated("One.kt"), "public data class OnInt(")
    }
}
