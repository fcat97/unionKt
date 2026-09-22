import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    `maven-publish`
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    withSourcesJar()
}

dependencies {
    implementation(project(":annotations"))
    implementation(libs.ksp.api)

    // KotlinPoet builds the generated source as a model (imports, nullability and
    // generics are its problem, not ours). kotlinpoet-ksp adds KSType.toTypeName()
    // and FileSpec.writeTo(CodeGenerator, Dependencies).
    implementation(libs.kotlinpoet)
    implementation(libs.kotlinpoet.ksp)
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])

            // Never hardcoded: both come from the root build script, which reads
            // them from JitPack's environment when running there.
            groupId = project.group.toString()
            artifactId = project.name
            version = project.version.toString()

            pom {
                name.set("unionKt processor")
                description.set("KSP symbol processor that generates sealed-interface unions from @Union markers.")
                url.set("https://github.com/fcat97/unionKt")
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }
                scm {
                    url.set("https://github.com/fcat97/unionKt")
                    connection.set("scm:git:https://github.com/fcat97/unionKt.git")
                }
            }
        }
    }
}
