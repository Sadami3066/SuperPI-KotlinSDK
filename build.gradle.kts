import org.gradle.api.publish.PublishingExtension

plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

val sdkVersion: String = providers.gradleProperty("sdkVersion").getOrElse("0.7.1")

subprojects {
    group = "io.github.whywood.superpi.sdk"
    version = sdkVersion

    plugins.withId("maven-publish") {
        extensions.configure<PublishingExtension> {
            repositories {
                maven {
                    name = "pages"
                    url = uri(rootProject.layout.buildDirectory.dir("maven-repo"))
                }
            }
        }
    }
}
