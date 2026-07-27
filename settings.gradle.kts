pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("androidx.*")
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            content {
                includeGroupByRegex("androidx.*")
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
            }
        }
        mavenCentral()
        maven {
            url = uri("https://devrepo.tmapadmin.com/repository/tmap-sdk-release/")
        }
        // 카카오내비 SDK(KNSDK) - 경로계산+실시간안내를 카카오로 우회하기 위해 추가.
        maven {
            url = uri("https://devrepo.kakaomobility.com/repository/kakao-mobility-android-knsdk-public/")
        }
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "TmapNda"
include(":app")
