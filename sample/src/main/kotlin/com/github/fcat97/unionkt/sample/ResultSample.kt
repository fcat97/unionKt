package com.github.fcat97.unionkt.sample

import com.github.fcat97.unionkt.Union

/**
 * A private marker. KSP generates `internal sealed interface Result` next to it,
 * in this same package — private would be unusable, since the union necessarily
 * lands in its own file.
 */
@Union(Int::class, String::class, User::class)
private interface ResultSpec

/**
 * The exhaustiveness guarantee. Delete any one of these branches and the build
 * fails with "'when' expression must be exhaustive"; the compiler, not the
 * processor, enforces it, because `Result` is a real sealed interface.
 */
internal fun describe(result: Result): String = when (result) {
    is Result.OnInt -> "int=${result.value}"
    is Result.OnString -> "string=${result.value}"
    is Result.OnUser -> "user=${result.value.name}"
}
