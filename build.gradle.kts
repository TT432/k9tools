plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.3.20"
    id("org.jetbrains.intellij.platform") version "2.16.0"
}

group = "io.github.tt432"
version = "1.3.0-SNAPSHOT"

repositories {
    mavenCentral()
    intellijPlatform.defaultRepositories()
}

// Configure Gradle IntelliJ Plugin
// Read more: https://plugins.jetbrains.com/docs/intellij/tools-gradle-intellij-plugin.html
dependencies {
    intellijPlatform {
        intellijIdea("2025.3")
        bundledPlugin("com.intellij.java")
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "253"
        }
    }
}

tasks {
    // Set the JVM compatibility versions
    withType<JavaCompile> {
        sourceCompatibility = "21"
        targetCompatibility = "21"
    }

    patchPluginXml {
        sinceBuild.set("252")
//        untilBuild.set("260.*")
    }

    signPlugin {
        certificateChain.set(System.getenv("CERTIFICATE_CHAIN"))
        privateKey.set(System.getenv("PRIVATE_KEY"))
        password.set(System.getenv("PRIVATE_KEY_PASSWORD"))
    }

    publishPlugin {
        token.set(System.getenv("PUBLISH_TOKEN"))
    }

    processResources {
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
    }

    register("printVersion") {
        notCompatibleWithConfigurationCache("直接访问 project 对象")

        doLast {
            println(version.toString())
        }
    }
}

idea.module {
    isDownloadJavadoc = true
    isDownloadSources = true
}
