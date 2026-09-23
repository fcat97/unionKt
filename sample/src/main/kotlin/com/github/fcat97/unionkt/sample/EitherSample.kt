package com.github.fcat97.unionkt.sample

import com.github.fcat97.unionkt.Union

/** A generic union: the marker's type parameters become the cases `OnL` and `OnR`. */
@Union
public interface EitherSpec<L, R>

/** No casts: `Either.onR(it)` is an `Either<Nothing, Int>`, assignable to `Either<String, Int>`. */
public fun parseAge(text: String): Either<String, Int> =
    text.toIntOrNull()?.let { Either.onR(it) } ?: Either.onL("not a number: $text")

public fun describeAge(age: Either<String, Int>): String =
    age.fold(onL = { "error: $it" }, onR = { "age $it" })
