package com.github.fcat97.unionkt.processor

import com.tschuchort.compiletesting.SourceFile
import kotlin.test.Test

/**
 * Flattening a marker that comes from a dependency. This only works because `@Union`
 * has BINARY retention: with SOURCE retention the annotation is gone from the library's
 * class files and the marker would become a plain `OnShapeSpec` case.
 */
class UnionCrossModuleTest {

    @Test
    fun `a marker from a dependency is flattened`() {
        val library = compileWithUnionProcessor(
            SourceFile.kotlin(
                "Shapes.kt",
                """
                package lib

                import com.github.fcat97.unionkt.Union

                data class Circle(val radius: Int)
                data class Square(val side: Int)

                @Union(Circle::class, Square::class)
                interface ShapeSpec
                """.trimIndent(),
            ),
        ).assertSucceeded()

        compileWithUnionProcessor(
            SourceFile.kotlin(
                "Item.kt",
                """
                package app

                import com.github.fcat97.unionkt.Union
                import lib.Circle
                import lib.Shape
                import lib.ShapeSpec
                import lib.Square

                @Union(Int::class, ShapeSpec::class)
                interface ItemSpec

                fun describe(i: Item): String = when (i) {
                    is Item.OnInt -> "int"
                    is Item.OnCircle -> "circle"
                    is Item.OnSquare -> "square"
                }

                fun verify() {
                    check(Shape(Circle(1)).toItem() == Item.OnCircle(Circle(1)))
                    check(Shape(Square(2)).toItem() == Item.OnSquare(Square(2)))
                }
                """.trimIndent(),
            ),
            classpath = listOf(library.outputDirectory),
        ).assertSucceeded().call("app.ItemKt", "verify")
    }
}
