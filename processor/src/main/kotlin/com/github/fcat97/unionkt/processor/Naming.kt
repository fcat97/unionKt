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
