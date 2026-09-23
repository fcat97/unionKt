package com.github.fcat97.unionkt.processor

import com.tschuchort.compiletesting.SourceFile
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

/** A member that is itself a @Union marker contributes its cases directly. */
class UnionFlatteningTest {

    private val shapes = SourceFile.kotlin(
        "Shapes.kt",
        """
        package test

        import com.github.fcat97.unionkt.Union

        data class Circle(val radius: Int)
        data class Square(val side: Int)

        @Union(Circle::class, Square::class)
        interface ShapeSpec
        """.trimIndent(),
    )

    @Test
    fun `a nested marker's cases are inlined and a conversion is generated`() {
        compileWithUnionProcessor(
            shapes,
            SourceFile.kotlin(
                "Item.kt",
                """
                package test

                import com.github.fcat97.unionkt.Union

                @Union(Int::class, ShapeSpec::class)
                interface ItemSpec

                fun describe(i: Item): String = when (i) {
                    is Item.OnInt -> "int"
                    is Item.OnCircle -> "circle"
                    is Item.OnSquare -> "square"
                }

                fun verify() {
                    check(Shape(Circle(1)).toItem() == Item.OnCircle(Circle(1)))
                    check(Shape(Square(2)).toItem() == Item(Square(2)))
                }
                """.trimIndent(),
            ),
        ).assertSucceeded().call("test.ItemKt", "verify")
    }

    @Test
    fun `flattening is transitive and converts from every level`() {
        compileWithUnionProcessor(
            SourceFile.kotlin(
                "Levels.kt",
                """
                package test

                import com.github.fcat97.unionkt.Union

                data class Circle(val radius: Int)
                data class Triangle(val side: Int)

                @Union(Triangle::class)
                interface PolygonSpec

                @Union(Circle::class, PolygonSpec::class)
                interface ShapeSpec

                @Union(Int::class, ShapeSpec::class)
                interface ItemSpec

                fun describe(i: Item): String = when (i) {
                    is Item.OnInt -> "int"
                    is Item.OnCircle -> "circle"
                    is Item.OnTriangle -> "triangle"
                }

                fun verify() {
                    check(Polygon(Triangle(3)).toItem() == Item.OnTriangle(Triangle(3)))
                    check(Shape(Triangle(3)).toItem() == Item.OnTriangle(Triangle(3)))
                    check(Polygon(Triangle(3)).toShape() == Shape.OnTriangle(Triangle(3)))
                }
                """.trimIndent(),
            ),
        ).assertSucceeded().call("test.LevelsKt", "verify")
    }

    @Test
    fun `overlapping nested unions merge silently`() {
        compileWithUnionProcessor(
            SourceFile.kotlin(
                "Overlap.kt",
                """
                package test

                import com.github.fcat97.unionkt.Union

                @Union(Int::class, String::class)
                interface ASpec

                @Union(String::class, Long::class)
                interface BSpec

                @Union(ASpec::class, BSpec::class)
                interface CSpec

                fun describe(c: C): String = when (c) {
                    is C.OnInt -> "int"
                    is C.OnString -> "string"
                    is C.OnLong -> "long"
                }
                """.trimIndent(),
            ),
        ).assertSucceeded().assertDoesNotWarn("more than once")
    }

    @Test
    fun `a type listed twice directly is merged with a warning`() {
        val result = compileWithUnionProcessor(
            SourceFile.kotlin(
                "Dup.kt",
                """
                package test

                import com.github.fcat97.unionkt.Union

                @Union(Int::class, Int::class)
                interface DupSpec
                """.trimIndent(),
            ),
        ).assertSucceeded()

        result.assertWarns("@Union on 'DupSpec' lists 'kotlin.Int' more than once; the duplicates are merged.")
        assertEquals(1, Regex("data class OnInt\\(").findAll(result.generated("Dup.kt")).count())
    }

    @Test
    fun `a simple-name clash through flattening names the path`() {
        compileWithUnionProcessor(
            SourceFile.kotlin(
                "Clash.kt",
                """
                package test

                import com.github.fcat97.unionkt.Union

                class User

                @Union(test.other.User::class)
                interface PeopleSpec

                @Union(User::class, PeopleSpec::class)
                interface ClashSpec
                """.trimIndent(),
            ),
            SourceFile.kotlin("OtherUser.kt", "package test.other\n\nclass User"),
        ).assertFailedWith(
            "2 member types whose simple name is 'User'",
            "test.User, test.other.User via PeopleSpec",
        )
    }

    @Test
    fun `a flattening cycle is rejected`() {
        compileWithUnionProcessor(
            SourceFile.kotlin(
                "Cycle.kt",
                """
                package test

                import com.github.fcat97.unionkt.Union

                @Union(Int::class, BSpec::class)
                interface ASpec

                @Union(String::class, ASpec::class)
                interface BSpec
                """.trimIndent(),
            ),
        ).assertFailedWith("@Union on 'ASpec' has a flattening cycle: ASpec → BSpec → ASpec")
    }

    @Test
    fun `flattening a generic marker is rejected`() {
        compileWithUnionProcessor(
            SourceFile.kotlin(
                "GenericFlatten.kt",
                """
                package test

                import com.github.fcat97.unionkt.Union

                @Union
                interface EitherSpec<L, R>

                @Union(Int::class, EitherSpec::class)
                interface ItemSpec
                """.trimIndent(),
            ),
        ).assertFailedWith("@Union on 'ItemSpec' cannot flatten generic union 'EitherSpec'")
    }

    @Test
    fun `referencing the generated union nests it as one case`() {
        compileWithUnionProcessor(
            shapes,
            SourceFile.kotlin(
                "Nest.kt",
                """
                package test

                import com.github.fcat97.unionkt.Union

                @Union(Int::class, Shape::class)
                interface NestSpec

                fun describe(n: Nest): String = when (n) {
                    is Nest.OnInt -> "int"
                    is Nest.OnShape -> "shape"
                }
                """.trimIndent(),
            ),
        ).assertSucceeded()
    }

    @Test
    fun `a conversion takes the stricter visibility of the two unions`() {
        val result = compileWithUnionProcessor(
            SourceFile.kotlin(
                "Vis.kt",
                """
                package test

                import com.github.fcat97.unionkt.Union

                @Union(Int::class)
                internal interface SecretSpec

                @Union(String::class)
                interface OpenSpec

                @Union(SecretSpec::class, OpenSpec::class)
                interface MixSpec
                """.trimIndent(),
            ),
        ).assertSucceeded()

        val generated = result.generated("Mix.kt")
        assertContains(generated, "internal fun Secret.toMix(): Mix")
        assertContains(generated, "public fun Open.toMix(): Mix")
    }

    @Test
    fun `a generic union can flatten a concrete one`() {
        compileWithUnionProcessor(
            shapes,
            SourceFile.kotlin(
                "Wrap.kt",
                """
                package test

                import com.github.fcat97.unionkt.Union

                @Union(ShapeSpec::class)
                interface WrapSpec<T>

                fun wrapped(): Wrap<Int> = Shape(Circle(1)).toWrap()
                """.trimIndent(),
            ),
        ).assertSucceeded()
    }
}
