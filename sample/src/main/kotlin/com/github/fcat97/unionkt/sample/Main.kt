package com.github.fcat97.unionkt.sample

public fun main() {
    val values = listOf(
        Result.onInt(5),
        Result("hello"),
        Result(User(id = 1, name = "Ada")),
    )
    values.forEach { println(describe(it)) }
    println(values.first().intOrNull)

    println(size(Payload.onBoolean(true)))
    println(size(Payload.onDoubleArray(doubleArrayOf(1.0, 2.0))))
    println(size(Payload.onUser(User(id = 2, name = "Grace"))))

    println(describeAge(parseAge("42")))
    println(describeAge(parseAge("forty-two")))

    println(label(Shape(Circle(radius = 1.5)).toDrawable()))
    println(label(Drawable(Square(side = 2.0))))
}
