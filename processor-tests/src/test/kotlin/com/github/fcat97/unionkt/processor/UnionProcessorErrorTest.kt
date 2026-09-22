package com.github.fcat97.unionkt.processor

import com.tschuchort.compiletesting.SourceFile
import kotlin.test.Test

/**
 * Every precondition in the processor, asserted through a real compilation.
 *
 * These cannot live in `:sample` — a `logger.error` fails the build, so the only way
 * to assert on the failures is to run the compiler in-process and inspect its output.
 */
class UnionProcessorErrorTest {

    @Test
    fun `marker that is not an interface is rejected`() {
        compileWithUnionProcessor(
            SourceFile.kotlin(
                "BadKind.kt",
                """
                package test

                import com.github.fcat97.unionkt.Union

                @Union(Int::class)
                class BadKindSpec
                """.trimIndent(),
            ),
        ).assertFailedWith(
            "@Union may only be applied to an interface, but 'BadKindSpec' is a class",
            "declare it as 'interface BadKindSpec'",
        )
    }

    @Test
    fun `object marker is rejected`() {
        compileWithUnionProcessor(
            SourceFile.kotlin(
                "BadObject.kt",
                """
                package test

                import com.github.fcat97.unionkt.Union

                @Union(Int::class)
                object BadObjectSpec
                """.trimIndent(),
            ),
        ).assertFailedWith("@Union may only be applied to an interface, but 'BadObjectSpec' is an object")
    }

    @Test
    fun `marker without the Spec suffix is rejected`() {
        compileWithUnionProcessor(
            SourceFile.kotlin(
                "BadName.kt",
                """
                package test

                import com.github.fcat97.unionkt.Union

                @Union(Int::class)
                interface BadName
                """.trimIndent(),
            ),
        ).assertFailedWith(
            "@Union marker 'BadName' must be named '<Union>Spec'",
            "Rename it to e.g. 'BadNameSpec'",
        )
    }

    @Test
    fun `marker named exactly Spec is rejected`() {
        // The suffix has to be a *suffix*: stripping it would leave an empty name.
        compileWithUnionProcessor(
            SourceFile.kotlin(
                "OnlySpec.kt",
                """
                package test

                import com.github.fcat97.unionkt.Union

                @Union(Int::class)
                interface Spec
                """.trimIndent(),
            ),
        ).assertFailedWith("@Union marker 'Spec' must be named '<Union>Spec'")
    }

    @Test
    fun `marker in the default package is rejected`() {
        compileWithUnionProcessor(
            SourceFile.kotlin(
                "NoPackage.kt",
                """
                import com.github.fcat97.unionkt.Union

                @Union(Int::class)
                interface RootSpec
                """.trimIndent(),
            ),
        ).assertFailedWith("must live in a named package")
    }

    @Test
    fun `marker with type parameters is rejected`() {
        compileWithUnionProcessor(
            SourceFile.kotlin(
                "Generic.kt",
                """
                package test

                import com.github.fcat97.unionkt.Union

                @Union(Int::class)
                interface GenericSpec<T>
                """.trimIndent(),
            ),
        ).assertFailedWith(
            "@Union marker 'GenericSpec' declares type parameters",
            "which the generated union cannot carry",
        )
    }

    @Test
    fun `union with no member types is rejected`() {
        compileWithUnionProcessor(
            SourceFile.kotlin(
                "Empty.kt",
                """
                package test

                import com.github.fcat97.unionkt.Union

                @Union
                interface EmptySpec
                """.trimIndent(),
            ),
        ).assertFailedWith(
            "@Union on 'EmptySpec' declares no member types",
            "A union needs at least one",
        )
    }

    @Test
    fun `member types sharing a simple name are rejected`() {
        compileWithUnionProcessor(
            SourceFile.kotlin(
                "Clash.kt",
                """
                package test

                import com.github.fcat97.unionkt.Union

                class User

                @Union(User::class, test.other.User::class)
                interface ClashSpec
                """.trimIndent(),
            ),
            SourceFile.kotlin(
                "OtherUser.kt",
                """
                package test.other

                class User
                """.trimIndent(),
            ),
        ).assertFailedWith(
            "2 member types whose simple name is 'User'",
            "would generate clashing 'OnUser' cases",
        )
    }

    @Test
    fun `three member types sharing a simple name are all reported`() {
        compileWithUnionProcessor(
            SourceFile.kotlin(
                "Clash3.kt",
                """
                package test

                import com.github.fcat97.unionkt.Union

                class Id

                @Union(Id::class, test.a.Id::class, test.b.Id::class)
                interface TripleSpec
                """.trimIndent(),
            ),
            SourceFile.kotlin("A.kt", "package test.a\n\nclass Id"),
            SourceFile.kotlin("B.kt", "package test.b\n\nclass Id"),
        ).assertFailedWith(
            "3 member types whose simple name is 'Id'",
            "test.Id, test.a.Id, test.b.Id",
        )
    }

    @Test
    fun `two markers resolving to the same union name are rejected`() {
        // Reachable because a nested marker still generates a top-level union into the
        // file's package, so it can collide with a top-level marker of the same name.
        compileWithUnionProcessor(
            SourceFile.kotlin(
                "Duplicate.kt",
                """
                package test

                import com.github.fcat97.unionkt.Union

                @Union(Int::class)
                interface ResultSpec

                class Holder {
                    @Union(String::class)
                    interface ResultSpec
                }
                """.trimIndent(),
            ),
        ).assertFailedWith(
            "would generate 'test.Result'",
            "another marker in the same package already generates",
        )
    }

    @Test
    fun `an unresolvable member type is reported`() {
        compileWithUnionProcessor(
            SourceFile.kotlin(
                "Unresolved.kt",
                """
                package test

                import com.github.fcat97.unionkt.Union

                @Union(Int::class, Missing::class)
                interface BrokenSpec
                """.trimIndent(),
            ),
        ).assertFailedWith("Unresolved reference")
    }
}
