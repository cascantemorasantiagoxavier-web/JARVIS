// Todos los plugins se cargan en el classpath raíz para que AGP y el plugin de Kotlin
// compartan classloader. AGP solo se añade cuando se compila :app, de modo que :core
// pueda compilarse en entornos sin acceso a Google Maven (-Pjarvis.coreOnly=true).
buildscript {
    val coreOnly = providers.gradleProperty("jarvis.coreOnly").orNull == "true"
    repositories {
        if (!coreOnly) google()
        mavenCentral()
        gradlePluginPortal()
    }
    dependencies {
        classpath(libs.plugin.kotlin)
        classpath(libs.plugin.kotlin.serialization)
        if (!coreOnly) {
            classpath(libs.plugin.android)
            classpath(libs.plugin.kotlin.compose)
            classpath(libs.plugin.ksp)
        }
    }
}
