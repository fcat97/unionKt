package com.github.fcat97.unionkt.sample

public fun main() {
    val values = listOf(
        Result.onInt(5),
        Result.onString("hello"),
        Result.onUser(User(id = 1, name = "Ada")),
    )
    values.forEach { println(describe(it)) }

    println(size(Payload.onBoolean(true)))
    println(size(Payload.onDoubleArray(doubleArrayOf(1.0, 2.0))))
    println(size(Payload.onUser(User(id = 2, name = "Grace"))))
}
