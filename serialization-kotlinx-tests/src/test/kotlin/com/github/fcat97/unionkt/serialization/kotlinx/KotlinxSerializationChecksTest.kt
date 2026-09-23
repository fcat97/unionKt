package com.github.fcat97.unionkt.serialization.kotlinx

import com.tschuchort.compiletesting.SourceFile
import kotlin.test.Test
import kotlin.test.assertEquals

/** The extension's compile-time checks (spec §4). */
class KotlinxSerializationChecksTest {

    @Test
    fun `a star-projected member is rejected`() {
        compileWithSerialization(
            SourceFile.kotlin(
                "BadList.kt",
                """
                package test

                import com.github.fcat97.unionkt.Union

                @Union(List::class, Int::class)
                interface BadListSpec
                """.trimIndent(),
            ),
        ).assertFailedWith(
            "@Union on 'BadListSpec' cannot serialize member 'List<*>': a class literal cannot say its element type.",
        )
    }

    @Test
    fun `a class without @Serializable is rejected`() {
        compileWithSerialization(
            SourceFile.kotlin(
                "Money.kt",
                """
                package test

                import com.github.fcat97.unionkt.Union

                class Money(val cents: Long)

                @Union(Money::class)
                interface MoneySpec
                """.trimIndent(),
            ),
        ).assertFailedWith("@Union on 'MoneySpec' cannot serialize member 'test.Money': it is not @Serializable.")
    }

    @Test
    fun `a java type is rejected`() {
        compileWithSerialization(
            SourceFile.kotlin(
                "Ids.kt",
                """
                package test

                import com.github.fcat97.unionkt.Union

                @Union(java.util.UUID::class, String::class)
                interface IdSpec
                """.trimIndent(),
            ),
        ).assertFailedWith("@Union on 'IdSpec' cannot serialize member 'java.util.UUID': it is not @Serializable.")
    }

    @Test
    fun `kotlin types, enums and @Serializable classes pass`() {
        compileWithSerialization(
            SourceFile.kotlin(
                "Fine.kt",
                """
                package test

                import com.github.fcat97.unionkt.Union
                import kotlinx.serialization.Serializable

                enum class Color { RED }

                @Serializable
                data class User(val name: String)

                @Union(Int::class, IntArray::class, Color::class, User::class)
                interface FineSpec
                """.trimIndent(),
            ),
        ).assertSucceeded()
    }

    @Test
    fun `a missing kotlinx runtime is reported once`() {
        val result = compileWithSerialization(
            SourceFile.kotlin(
                "Plain.kt",
                """
                package test

                import com.github.fcat97.unionkt.Union

                @Union(Int::class)
                interface PlainSpec

                @Union(String::class)
                interface OtherSpec
                """.trimIndent(),
            ),
            classpathFilter = { "kotlinx-serialization" !in it.name },
        ).assertFailedWith(
            "serialization-kotlinx is installed, but kotlinx-serialization-core is not on the classpath. " +
                "Add the kotlinx-serialization-json dependency.",
        )

        assertEquals(1, Regex("serialization-kotlinx is installed").findAll(result.messages).count(), result.messages)
    }
}
