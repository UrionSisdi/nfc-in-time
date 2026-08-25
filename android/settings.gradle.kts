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
        // Telegram publishes its login SDK to GitHub Packages only, and reading it
        // needs a personal token with `read:packages` — gpr.user / gpr.key in
        // ~/.gradle/gradle.properties, or GITHUB_USERNAME / GITHUB_TOKEN in CI.
        maven {
            url = uri("https://maven.pkg.github.com/TelegramMessenger/telegram-login-android")
            credentials {
                username = providers.gradleProperty("gpr.user").orNull
                    ?: System.getenv("GITHUB_USERNAME")
                password = providers.gradleProperty("gpr.key").orNull
                    ?: System.getenv("GITHUB_TOKEN")
            }
            content { includeGroup("org.telegram") }
        }
    }
}

rootProject.name = "nfc-in-time"
include(":app")
