plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // StateFlow, SharedFlow, CoroutineScope and CoroutineDispatcher are part
    // of the public Core API, so consumers need coroutines on their compile
    // classpath as well as at runtime.
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

    testImplementation(kotlin("test"))
}
