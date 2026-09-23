package com.github.fcat97.unionkt.processor

import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.configureKsp
import com.tschuchort.compiletesting.kspSourcesDir
import com.tschuchort.compiletesting.useKsp2
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Runs the Kotlin compiler in-process with the unionKt processors attached.
 *
 * The `:sample` module can only prove the *happy* path: a processor that calls
 * `logger.error` fails the build, so failure cases cannot live in a normal source
 * set. Compiling from strings here is what makes the error paths assertable.
 *
 * @param classpath extra dependencies, e.g. a "library" compiled by an earlier call.
 * @param compilerPlugins compiler plugins to run, e.g. kotlinx.serialization's.
 * @param classpathFilter when set, the compiled sources see only the host classpath
 *   entries it accepts (instead of the whole host classpath). The processor itself still
 *   runs from the host classpath either way.
 */
public fun compileWithUnionProcessor(
    vararg sources: SourceFile,
    classpath: List<File> = emptyList(),
    compilerPlugins: List<CompilerPluginRegistrar> = emptyList(),
    classpathFilter: ((File) -> Boolean)? = null,
): UnionCompilationResult {
    val compilation = KotlinCompilation().apply {
        this.sources = sources.toList()
        // Puts the :annotations module (and kotlin-stdlib) on the compiled sources'
        // classpath, so `import com.github.fcat97.unionkt.Union` resolves.
        inheritClassPath = classpathFilter == null
        classpaths = classpath + (classpathFilter?.let { accept -> hostClasspath().filter(accept) } ?: emptyList())
        compilerPluginRegistrars = compilerPlugins
        useKsp2()
        configureKsp {
            symbolProcessorProviders += UnionProcessorProvider()
            symbolProcessorProviders += DeriveProcessorProvider()
        }
    }

    val result = compilation.compile()
    return UnionCompilationResult(result, compilation.kspSourcesDir)
}

private fun hostClasspath(): List<File> =
    System.getProperty("java.class.path").split(File.pathSeparator).filter { it.isNotBlank() }.map(::File)

public class UnionCompilationResult(
    private val result: JvmCompilationResult,
    private val kspSourcesDir: File,
) {
    public val exitCode: KotlinCompilation.ExitCode get() = result.exitCode
    public val messages: String get() = result.messages

    /** The compiled classes, to pass as `classpath` to a later compilation. */
    public val outputDirectory: File get() = result.outputDirectory

    private val generatedFiles: List<File>
        get() = kspSourcesDir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    /** Asserts the compilation succeeded, printing the compiler output when it did not. */
    public fun assertSucceeded(): UnionCompilationResult = apply {
        assertEquals(
            KotlinCompilation.ExitCode.OK,
            exitCode,
            "expected compilation to succeed, but it failed with:\n$messages",
        )
    }

    /** Asserts the compilation failed *and* that the failure is the expected diagnostic. */
    public fun assertFailedWith(vararg expectedFragments: String): UnionCompilationResult = apply {
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

    public fun assertWarns(fragment: String): UnionCompilationResult = apply {
        assertTrue(
            messages.contains(fragment),
            "expected a warning containing:\n  $fragment\nbut the output was:\n$messages",
        )
    }

    public fun assertDoesNotWarn(fragment: String): UnionCompilationResult = apply {
        assertTrue(
            !messages.contains(fragment),
            "expected no warning containing:\n  $fragment\nbut the output was:\n$messages",
        )
    }

    /** The text of a generated file, e.g. `generated("Result.kt")`. */
    public fun generated(fileName: String): String {
        val file = generatedFiles.firstOrNull { it.name == fileName }
        return requireNotNull(file) {
            "no generated file named '$fileName'; generated: ${generatedFiles.map { it.name }}"
        }.readText()
    }

    public fun generatedFileNames(): List<String> = generatedFiles.map { it.name }.sorted()

    /**
     * Runs a top-level, no-argument function from the compiled sources and returns its
     * result, e.g. `call("test.UseKt", "verify")`. A failing `check` inside it fails the test.
     */
    public fun call(className: String, functionName: String): Any? =
        result.classLoader.loadClass(className).getMethod(functionName).invoke(null)
}
