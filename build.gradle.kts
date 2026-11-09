plugins {
    kotlin("jvm") version "2.0.21"
    `maven-publish`
}

group = "kz.tungo"
version = "0.1.0"

repositories {
    mavenCentral()
}

java {
    withSourcesJar()
    withJavadocJar()
}

kotlin {
    jvmToolchain(17)
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "tungo-sdk-kotlin"
            from(components["java"])
            pom {
                name.set("Tungo AI Kotlin SDK")
                description.set("Official Kotlin SDK for Tungo AI Platform API (Kotlin 2.0+, zero dependencies)")
                url.set("https://tungo.kz")
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }
                developers {
                    developer {
                        id.set("tungo")
                        name.set("Tungo AI")
                        email.set("dev@tungo.kz")
                    }
                }
                scm {
                    connection.set("scm:git:git://github.com/TungoAI/tungo-sdk-kotlin.git")
                    url.set("https://github.com/TungoAI/tungo-sdk-kotlin")
                }
            }
        }
    }
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/TungoAI/tungo-sdk-kotlin")
            credentials {
                username = System.getenv("GITHUB_ACTOR")
                password = System.getenv("GITHUB_TOKEN")
            }
        }
    }
}
