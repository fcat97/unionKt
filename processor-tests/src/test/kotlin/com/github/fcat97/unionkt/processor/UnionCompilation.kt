package com.github.fcat97.unionkt.processor

import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.configureKsp
import com.tschuchort.compiletesting.kspSourcesDir
import com.tschuchort.compiletesting.useKsp2
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Runs the Kotlin compiler in-process with [UnionProcessorProvider] attached.
 *
 * The `:sample` module can only prove the *happy* path: a processor that calls
 * `logger.error` fails the build, so failure cases cannot live in a normal source
 * set. Compiling from strings here is what makes the error paths assertable.
 */
internal fun compileWithUnionProcessor(vararg sources: SourceFile): UnionCompilationResult {
    val compilation = KotlinCompilation().apply {
        this.sources = sources.toList()
        // Puts the :annotations module (and kotlin-stdlib) on the compiled sources'
        // classpath, so `import com.github.fcat97.unionkt.Union` resolves.
        inheritClassPath = true
        useKsp2()
        configureKsp {
            symbolProcessorProviders += UnionProcessorProvider()
        }
    }

    val result = compilation.compile()
    return UnionCompilationResult(result, compilation.kspSourcesDir)
}

internal class UnionCompilationResult(
    private val result: JvmCompilationResult,
    private val kspSourcesDir: File,
) {
    val exitCode: KotlinCompilation.ExitCode get() = result.exitCode
    val messages: String get() = result.messages

    private val generatedFiles: List<File>
        get() = kspSourcesDir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    /** Asserts the compilation succeeded, printing the compiler output when it did not. */
    fun assertSucceeded(): UnionCompilationResult = apply {
        assertEquals(
            KotlinCompilation.ExitCode.OK,
            exitCode,
            "expected compilation to succeed, but it failed with:\n$messages",
        )
    }

    /** Asserts the compilation failed *and* that the failure is the expected diagnostic. */
    fun assertFailedWith(vararg expectedFragments: String): UnionCompilationResult = apply {
        assertEquals(
            KotlinCompilation.ExitCode.COMPILATION_ERROR,
            exitCode,
            "expected compilation to fail, but it succeeded. Output:\n$messages",
        )
        expectedFragments.forEach { fragment ->
            assertTrue(
                messages.contains(fragment),
                "expected an error containing:\n  $fragment\nbut the output was:\n$messages",
            )
        }
    }

    fun assertWarns(fragment: String): UnionCompilationResult = apply {
        assertTrue(
            messages.contains(fragment),
            "expected a warning containing:\n  $fragment\nbut the output was:\n$messages",
        )
    }

    fun assertDoesNotWarn(fragment: String): UnionCompilationResult = apply {
        assertTrue(
            !messages.contains(fragment),
            "expected no warning containing:\n  $fragment\nbut the output was:\n$messages",
        )
    }

    /** The text of a generated file, e.g. `generated("Result.kt")`. */
    fun generated(fileName: String): String {
        val file = generatedFiles.firstOrNull { it.name == fileName }
        return requireNotNull(file) {
            "no generated file named '$fileName'; generated: ${generatedFiles.map { it.name }}"
        }.readText()
    }

    fun generatedFileNames(): List<String> = generatedFiles.map { it.name }.sorted()
}
