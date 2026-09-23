package com.github.fcat97.unionkt.processor

import com.github.fcat97.unionkt.api.UnionExtension
import com.github.fcat97.unionkt.api.UnionInfo
import com.github.fcat97.unionkt.api.UnionMemberInfo
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFile
import java.util.ServiceLoader

/**
 * Every extension registered in `META-INF/services`. KSP loads the whole `ksp`
 * configuration into one classloader, so an extension shipped as a separate `ksp(...)`
 * artifact is visible through the loader that loaded the API itself.
 */
internal fun loadExtensions(): List<UnionExtension> =
    ServiceLoader.load(UnionExtension::class.java, UnionExtension::class.java.classLoader).toList()

/** The read-only view of a resolved union handed to extensions. */
internal fun UnionModel.toInfo(marker: KSClassDeclaration, sources: List<KSFile>): UnionInfo = UnionInfo(
    markerName = markerName,
    marker = marker,
    unionType = unionType,
    visibility = visibility,
    typeParameters = typeParameters.map { it.declaredVariable() },
    members = members.map { member ->
        UnionMemberInfo(
            simpleName = member.simpleName,
            typeName = member.typeName,
            caseClass = unionType.nestedClass(CASE_PREFIX + member.simpleName),
            typeParameterName = member.typeParameter?.name,
            declaration = member.declaration,
        )
    },
    sources = sources,
)
