package com.github.fcat97.unionkt.processor

import com.tschuchort.compiletesting.SourceFile
import kotlin.test.Test
import kotlin.test.assertContains

/** The processor's extension API, exercised through [TestExtension]. */
class UnionExtensionTest {

    @Test
    fun `an extension's annotations are added to the union`() {
        val generated = compileWithUnionProcessor(
            SourceFile.kotlin(
                "ExtAnnotated.kt",
                """
                package test

                import com.github.fcat97.unionkt.Union

                @Union(Int::class)
                interface ExtAnnotatedSpec
                """.trimIndent(),
            ),
        ).assertSucceeded().generated("ExtAnnotated.kt")

        assertContains(generated, "@Deprecated(\"from extension\")")
    }

    @Test
    fun `an extension can generate its own files`() {
        compileWithUnionProcessor(
            SourceFile.kotlin(
                "ExtGenerated.kt",
                """
                package test

                import com.github.fcat97.unionkt.Union

                @Union(Int::class)
                interface ExtGeneratedSpec

                fun verify() {
                    check(ExtGenerated(1).describeFromExtension() == "from extension")
                }
                """.trimIndent(),
            ),
        ).assertSucceeded().call("test.ExtGeneratedKt", "verify")
    }

    @Test
    fun `an extension sees the fully resolved union`() {
        val report = compileWithUnionProcessor(
            SourceFile.kotlin(
                "ExtInfo.kt",
                """
                package test

                import com.github.fcat97.unionkt.Union

                data class Circle(val radius: Int)

                @Union(Circle::class)
                internal interface ShapeSpec

                @Union(Int::class, ShapeSpec::class)
                internal interface ExtInfoSpec<T>
                """.trimIndent(),
            ),
        ).assertSucceeded().generated("ExtInfoReport.kt")

        assertContains(report, "union=test.ExtInfo visibility=INTERNAL marker=ExtInfoSpec")
        assertContains(report, "typeParameters=[out T]")
        assertContains(report, "member Int case=test.ExtInfo.OnInt type=kotlin.Int typeParameter=null declaration=kotlin.Int")
        assertContains(report, "member Circle case=test.ExtInfo.OnCircle type=test.Circle typeParameter=null declaration=test.Circle")
        assertContains(report, "member T case=test.ExtInfo.OnT type=T typeParameter=T declaration=null")
    }

    @Test
    fun `an extension that throws becomes a compile error`() {
        compileWithUnionProcessor(
            SourceFile.kotlin(
                "ExtThrows.kt",
                """
                package test

                import com.github.fcat97.unionkt.Union

                @Union(Int::class)
                interface ExtThrowsSpec
                """.trimIndent(),
            ),
        ).assertFailedWith(
            "unionKt extension 'com.github.fcat97.unionkt.processor.TestExtension' failed on 'ExtThrowsSpec': boom",
        )
    }
}
