import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// NOT published. Compiles and tests the generated code on JVM, JS, Linux and Windows
// (compile only) here, and on the iOS simulator and macOS in CI.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

kotlin {
    jvm {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_17
        }
    }
    js {
        nodejs()
    }
    linuxX64()
    mingwX64()
    iosSimulatorArm64()
    macosArm64()

    sourceSets {
        commonMain.dependencies {
            implementation(project(":annotations"))
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

// KSP per target, as the README documents for multiplatform users.
dependencies {
    listOf("kspJvm", "kspJs", "kspLinuxX64", "kspMingwX64", "kspIosSimulatorArm64", "kspMacosArm64").forEach {
        add(it, project(":processor"))
        add(it, project(":serialization-kotlinx"))
    }
}

// GeneratedCodeTest reads the JS and Linux KSP output.
tasks.named("jvmTest") {
    dependsOn("kspKotlinJs", "kspKotlinLinuxX64")
}
