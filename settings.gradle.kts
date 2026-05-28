@file:Suppress("ktlint")

pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
    }
}

include(
    ":libretrodroid",
    ":retrograde-util",
    ":retrograde-app-shared",
    ":lemuroid-touchinput",
    ":lemuroid-app",
    ":lemuroid-metadata-libretro-db",
    ":lemuroid-app-ext-free",
    ":lemuroid-app-ext-play",
    ":baselineprofile"
)
