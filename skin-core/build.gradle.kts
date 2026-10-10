// Pure Kotlin on purpose: every number Nila shows a parent comes from here, so
// all of it runs and is tested on the JVM without a phone or an emulator.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin { jvmToolchain(17) }

dependencies {
    testImplementation(libs.junit)
}
