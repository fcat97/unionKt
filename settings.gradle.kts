pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "unionKt"

include(":annotations")
include(":processor")
include(":processor-api")
include(":processor-tests")
include(":serialization-kotlinx")
include(":serialization-kotlinx-tests")

// :sample is never published. It exists only so the generated union is compiled
// in-tree, which is what proves `when` exhaustiveness actually holds.
include(":sample")
