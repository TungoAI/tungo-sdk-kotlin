plugins {
    kotlin("jvm") version "2.0.21"
}

group = "kz.tungo"
version = "0.1.0"

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(17)
}
