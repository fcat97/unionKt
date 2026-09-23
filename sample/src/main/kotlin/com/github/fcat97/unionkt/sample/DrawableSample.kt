package com.github.fcat97.unionkt.sample

import com.github.fcat97.unionkt.Union

public data class Circle(val radius: Double)
public data class Square(val side: Double)

@Union(Circle::class, Square::class)
public interface ShapeSpec

/** Flattened: `Drawable` has the cases OnUser, OnCircle and OnSquare, plus `Shape.toDrawable()`. */
@Union(User::class, ShapeSpec::class)
public interface DrawableSpec

public fun label(drawable: Drawable): String = when (drawable) {
    is Drawable.OnUser -> "user ${drawable.value.name}"
    is Drawable.OnCircle -> "circle r=${drawable.value.radius}"
    is Drawable.OnSquare -> "square side=${drawable.value.side}"
}
