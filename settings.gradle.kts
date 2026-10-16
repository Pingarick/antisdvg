pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
        // Fallback mirror (last resort, e.g. restricted regions).
        maven("https://maven.aliyun.com/repository/google")
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Fallback mirrors, kept last so official repos win.
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
    }
}

rootProject.name = "Anti-SVDG"
include(":app")
