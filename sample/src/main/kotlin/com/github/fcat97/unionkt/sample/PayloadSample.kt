package com.github.fcat97.unionkt.sample

import com.github.fcat97.unionkt.Union

/** A public marker, so the generated `Payload` union is public too. */
@Union(Boolean::class, DoubleArray::class, User::class)
public interface PayloadSpec

public fun size(payload: Payload): Int = when (payload) {
    is Payload.OnBoolean -> if (payload.value) 1 else 0
    is Payload.OnDoubleArray -> payload.value.size
    is Payload.OnUser -> payload.value.name.length
}
