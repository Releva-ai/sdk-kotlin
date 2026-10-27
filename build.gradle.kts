// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    id("com.android.library") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    // Fails `check` (and so CI's `:releva-sdk:build`) when the public API changes without a
    // deliberate `./gradlew apiDump`, so a breaking change can't ship by accident.
    id("org.jetbrains.kotlinx.binary-compatibility-validator") version "0.16.3"
}
