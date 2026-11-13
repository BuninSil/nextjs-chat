pluginManagement {
    repositories {
        google()
        // Google-hosted mirror of Maven Central: avoids transient HTTP 429
        // responses from repo.maven.apache.org in CI.
        maven {
            url = uri("https://maven-central.storage-download.googleapis.com/maven2/")
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        // Keep the mirror ahead of Maven Central so CI can fall back cleanly.
        maven {
            url = uri("https://maven-central.storage-download.googleapis.com/maven2/")
        }
        mavenCentral()
    }
}

rootProject.name = "MlbbServerOverlay"
include(":app")
