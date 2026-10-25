import com.vanniktech.maven.publish.MavenPublishBaseExtension

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.maven.publish) apply false
    alias(libs.plugins.dokka) apply false
    alias(libs.plugins.intellij.platform) apply false
}

allprojects {
    group = "io.github.akshaychordiya.pose"
    version = "0.6.5"

    plugins.withId("com.vanniktech.maven.publish") {
        extensions.configure<MavenPublishBaseExtension> {
            publishToMavenCentral(automaticRelease = true)
            signAllPublications()

            pom {
                name.set(project.name)
                description.set("Auto-generates Jetpack Compose @Preview functions via KSP.")
                url.set("https://github.com/AkshayChordiya/Pose")
                licenses {
                    license {
                        name.set("The Apache Software License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
                developers {
                    developer {
                        id.set("akshaychordiya")
                        name.set("Akshay Chordiya")
                    }
                }
                scm {
                    url.set("https://github.com/AkshayChordiya/Pose")
                    connection.set("scm:git:git://github.com/AkshayChordiya/Pose.git")
                    developerConnection.set("scm:git:ssh://git@github.com/AkshayChordiya/Pose.git")
                }
            }
        }
    }
}
