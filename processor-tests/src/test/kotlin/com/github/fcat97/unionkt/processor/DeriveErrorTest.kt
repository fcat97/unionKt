package com.github.fcat97.unionkt.processor

import com.tschuchort.compiletesting.SourceFile
import kotlin.test.Test

/** Every `@Derive` precondition (spec §5). */
class DeriveErrorTest {

    private fun source(name: String, body: String) = SourceFile.kotlin(
        "$name.kt",
        "package test\n\nimport com.github.fcat97.unionkt.Derive\n\n$body",
    )

    @Test
    fun `a class that is not sealed is rejected`() {
        compileWithUnionProcessor(source("Plain", "@Derive\nclass Plain"))
            .assertFailedWith("@Derive may only be applied to a sealed class or interface, but 'Plain' is a class that is not sealed.")
    }

    @Test
    fun `an object and an enum are rejected`() {
        compileWithUnionProcessor(source("Kinds", "@Derive\nobject Single\n\n@Derive\nenum class Color { RED }"))
            .assertFailedWith("'Single' is an object.", "'Color' is an enum class.")
    }

    @Test
    fun `a target in the default package is rejected`() {
        compileWithUnionProcessor(
            SourceFile.kotlin("Root.kt", "@com.github.fcat97.unionkt.Derive\nsealed interface Root { object A : Root }"),
        ).assertFailedWith("@Derive target 'Root' must live in a named package")
    }

    @Test
    fun `a private target is rejected`() {
        compileWithUnionProcessor(
            source(
                "Hidden",
                "@Derive\nprivate sealed interface Hidden { data object A : Hidden }\n\n" +
                    "private class Box {\n    @Derive\n    sealed interface Inside { data object B : Inside }\n}",
            ),
        ).assertFailedWith(
            "@Derive target 'Hidden' is not visible to other files (it, or a class containing it, is private or protected). Make it internal or public.",
            "@Derive target 'Inside' is not visible to other files",
        )
    }

    @Test
    fun `a sealed type without subclasses is rejected`() {
        compileWithUnionProcessor(source("Empty", "@Derive\nsealed interface Empty"))
            .assertFailedWith("@Derive on 'Empty' found no subclasses. A sealed type needs at least one to derive helpers for.")
    }

    @Test
    fun `a private subclass is rejected`() {
        compileWithUnionProcessor(
            source("S", "@Derive\nsealed interface S {\n    private data class Hidden(val x: Int) : S\n    data object V : S\n}"),
        ).assertFailedWith(
            "@Derive on 'S' cannot reference subclass 'test.S.Hidden': it, or a class containing it, is private or protected, so the generated file cannot see it. Make it internal or public.",
        )
    }

    @Test
    fun `subclasses sharing a simple name are rejected`() {
        compileWithUnionProcessor(
            source("S", "@Derive\nsealed interface S\n\nclass A { class Item : S }\n\nclass B { class Item : S }"),
        ).assertFailedWith(
            "@Derive on 'S' has 2 subclasses whose simple name is 'Item' (test.A.Item, test.B.Item), which would generate clashing 'onItem' handlers. Rename one of them.",
        )
    }

    @Test
    fun `two targets generating the same file are rejected`() {
        compileWithUnionProcessor(
            source(
                "Clash",
                "@Derive\nsealed interface OuterState { data object A : OuterState }\n\n" +
                    "class Outer {\n    @Derive\n    sealed interface State { data object B : State }\n}",
            ),
        ).assertFailedWith("would generate 'test.OuterStateDerived.kt', which another @Derive target already generates. Rename one of them.")
    }
}
