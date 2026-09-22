import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// NOT published. No `maven-publish` here on purpose: this module exists only to
// compile against the generated unions, which is the exhaustiveness proof.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ksp)
}

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
    ksp(project(":processor"))
}
