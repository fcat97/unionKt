package com.github.fcat97.unionkt.processor

import com.github.fcat97.unionkt.Union

internal val UNION_ANNOTATION_NAME: String = requireNotNull(Union::class.qualifiedName)
internal val UNION_ANNOTATION_SIMPLE_NAME: String = requireNotNull(Union::class.simpleName)

internal const val TYPES_ARGUMENT = "types"
internal const val SPEC_SUFFIX = "Spec"
internal const val CASE_PREFIX = "On"
internal const val FACTORY_PREFIX = "on"
internal const val VALUE_NAME = "value"

/**
 * The union a marker generates: its name minus the `Spec` suffix, or null when the
 * name does not end in `Spec` or *is* `Spec`.
 */
internal fun unionNameOf(markerName: String): String? =
    if (markerName.endsWith(SPEC_SUFFIX) && markerName.length > SPEC_SUFFIX.length) {
        markerName.dropLast(SPEC_SUFFIX.length)
    } else {
        null
    }

/**
 * The stem of a case's `<stem>OrNull` accessor: the simple name decapitalised Kotlin
 * style. A leading run of capitals is lowercased, except for its last letter when that
 * letter starts the next word: `Int → int`, `DoubleArray → doubleArray`, `URL → url`,
 * `URLParser → urlParser`, `L → l`.
 */
internal fun accessorStem(simpleName: String): String {
    val capitals = simpleName.takeWhile(Char::isUpperCase).length
    if (capitals == 0) return simpleName
    val startsNextWord = capitals > 1 && capitals < simpleName.length && simpleName[capitals].isLowerCase()
    val lowered = if (startsNextWord) capitals - 1 else capitals
    return simpleName.take(lowered).lowercase() + simpleName.drop(lowered)
}

/** The first of `T`, `T1`, `T2`, … that is not in [taken]. */
internal fun freeTypeVariableName(taken: Set<String>): String =
    generateSequence(0) { it + 1 }
        .map { if (it == 0) "T" else "T$it" }
        .first { it !in taken }
