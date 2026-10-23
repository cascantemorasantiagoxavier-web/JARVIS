// J.A.R.V.I.S. — proyecto Android.
//  :core  Kotlin/JVM puro (cerebro, herramientas, protocolo Realtime, clientes de red).
//         Compila y se prueba solo con Maven Central.
//  :app   Aplicación Android (Compose, Room, WebRTC, servicios). Requiere Google Maven.
// Para compilar y probar solo :core en un entorno sin Google Maven:
//   gradle -Pjarvis.coreOnly=true :core:test
val coreOnly = providers.gradleProperty("jarvis.coreOnly").orNull == "true"

pluginManagement {
    repositories {
        if (providers.gradleProperty("jarvis.coreOnly").orNull != "true") google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (!coreOnly) google()
        mavenCentral()
    }
}

rootProject.name = "JARVIS"

include(":core")
if (!coreOnly) {
    include(":app")
}
