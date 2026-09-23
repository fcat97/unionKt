import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm
import com.vanniktech.maven.publish.SourcesJar
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.maven.publish)
}

description = "KSP processors that generate unions from @Union markers and helpers for @Derive sealed types."

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation(project(":annotations"))
    implementation(project(":processor-api"))
    implementation(libs.ksp.api)

    // KotlinPoet builds the generated source as a model (imports, nullability and
    // generics are its problem, not ours). kotlinpoet-ksp adds KSType.toTypeName()
    // and FileSpec.writeTo(CodeGenerator, Dependencies).
    implementation(libs.kotlinpoet)
    implementation(libs.kotlinpoet.ksp)
}

mavenPublishing {
    configure(KotlinJvm(javadocJar = JavadocJar.Empty(), sourcesJar = SourcesJar.Sources()))
}
