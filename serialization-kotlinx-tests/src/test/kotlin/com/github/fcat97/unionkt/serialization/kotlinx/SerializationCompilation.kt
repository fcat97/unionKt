package com.github.fcat97.unionkt.serialization.kotlinx

import com.github.fcat97.unionkt.processor.UnionCompilationResult
import com.github.fcat97.unionkt.processor.compileWithUnionProcessor
import com.tschuchort.compiletesting.SourceFile
import org.jetbrains.kotlinx.serialization.compiler.extensions.SerializationComponentRegistrar
import java.io.File

/** Compiles with the unionKt processor, this module's extension, and the serialization compiler plugin. */
internal fun compileWithSerialization(
    vararg sources: SourceFile,
    classpathFilter: ((File) -> Boolean)? = null,
): UnionCompilationResult = compileWithUnionProcessor(
    *sources,
    compilerPlugins = listOf(SerializationComponentRegistrar()),
    classpathFilter = classpathFilter,
)
