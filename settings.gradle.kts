pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "NilaSkin"

// One feature, split the way Nila will consume it. Each module has one job and
// talks to the next through a small interface, so Nila can take skin-core,
// skin-data, skin-ai and skin-ui as they are and drop :app.
include(":skin-core")   // pure Kotlin: colour maths, measurements, rules, food tables
include(":skin-data")   // Room storage and private photo files
include(":skin-ai")     // on-device models: Intern3.5-VL on the NPU, MediaPipe face landmarks
include(":skin-ui")     // Compose screens in Nila's design system
include(":app")         // the standalone host used to build and demo the feature
