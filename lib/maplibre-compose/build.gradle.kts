@file:OptIn(ExperimentalKotlinGradlePluginApi::class, ExperimentalComposeLibrary::class)

import org.jetbrains.compose.ExperimentalComposeLibrary
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSetTree
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
  id("library-conventions")
  id("android-library-conventions")
  id(libs.plugins.kotlin.multiplatform.get().pluginId)
  id(libs.plugins.kotlin.cocoapods.get().pluginId)
  id(libs.plugins.kotlin.composeCompiler.get().pluginId)
  id(libs.plugins.android.library.get().pluginId)
  id(libs.plugins.compose.get().pluginId)
  id(libs.plugins.mavenPublish.get().pluginId)
}

android { namespace = "dev.sargunv.maplibrecompose" }

mavenPublishing {
  pom {
    name = "MapLibre Compose"
    description = "Add interactive vector tile maps to your Compose app"
    url = "https://github.com/maplibre/maplibre-compose"
  }
}

val desktopResources: Configuration by
  configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
  }

dependencies {
  desktopResources(
    project(path = ":lib:maplibre-compose-webview", configuration = "jsBrowserDistribution")
  )
}

val copyDesktopResources by
  tasks.registering(Copy::class) {
    from(desktopResources)
    eachFile { path = "files/${path}" }
    into(project.layout.buildDirectory.dir(desktopResources.name))
  }

kotlin {
  androidTarget {
    compilerOptions { jvmTarget = project.getJvmTarget() }
    instrumentedTestVariant.sourceSetTree.set(KotlinSourceSetTree.test)
    publishLibraryVariants("release", "debug")
  }

  fun KotlinNativeTarget.configureIos() {
    compilations.getByName("main") {
      cinterops {
        val observer by creating {
          defFile(project.file("src/nativeInterop/cinterop/observer.def"))
          packageName("dev.sargunv.maplibrecompose.core.util")
        }
      }
    }
  }

  iosArm64 { configureIos() }
  iosSimulatorArm64 { configureIos() }
  iosX64 { configureIos() }

  jvm("desktop") { compilerOptions { jvmTarget = project.getJvmTarget() } }
  js(IR) { browser() }

  applyDefaultHierarchyTemplate()

  cocoapods {
    noPodspec()
    ios.deploymentTarget = project.properties["iosDeploymentTarget"]!!.toString()
    pod("MapLibre", libs.versions.maplibre.ios.get())
  }

  sourceSets {
    val desktopMain by getting

    listOf(iosMain, iosArm64Main, iosSimulatorArm64Main, iosX64Main).forEach {
      it { languageSettings { optIn("kotlinx.cinterop.ExperimentalForeignApi") } }
    }

    commonMain.dependencies {
      implementation(compose.foundation)
      implementation(compose.components.resources)
      api(libs.kermit)
      api(libs.spatialk.geojson)
      api(project(":lib:maplibre-compose-expressions"))
    }

    // used to share some implementation on platforms where Compose UI is backed by Skia directly
    // (e.g. all but Android, which is backed by the Android Canvas API)
    val skiaMain by creating { dependsOn(commonMain.get()) }

    // used to expose APIs only available on platforms backed by MapLibre Native
    // (e.g. Android and iOS, and maybe someday Desktop)
    val maplibreNativeMain by creating { dependsOn(commonMain.get()) }

    iosMain {
      dependsOn(skiaMain)
      dependsOn(maplibreNativeMain)
    }

    androidMain {
      dependsOn(maplibreNativeMain)
      dependencies {
        api(libs.maplibre.android)
        implementation(libs.maplibre.android.scalebar)
      }
    }

    // no idea why this is differently typed from the others
    desktopMain.apply {
      dependsOn(skiaMain)
      dependencies {
        implementation(compose.desktop.currentOs)
        implementation(libs.kotlinx.coroutines.swing)
        implementation(libs.webview)
      }
    }

    jsMain {
      dependsOn(skiaMain)
      dependencies {
        implementation(project(":lib:kotlin-maplibre-js"))
        implementation(project(":lib:compose-html-interop"))
      }
    }

    commonTest.dependencies {
      implementation(kotlin("test"))
      implementation(kotlin("test-common"))
      implementation(kotlin("test-annotations-common"))

      @OptIn(ExperimentalComposeLibrary::class) implementation(compose.uiTest)
    }

    androidUnitTest.dependencies { implementation(compose.desktop.currentOs) }

    androidInstrumentedTest.dependencies {
      implementation(compose.desktop.uiTestJUnit4)
      implementation(libs.androidx.composeUi.testManifest)
    }
  }
}

compose.resources {
  packageOfResClass = "dev.sargunv.maplibrecompose.generated"

  customDirectory(
    sourceSetName = "desktopMain",
    directoryProvider = layout.dir(copyDesktopResources.map { it.destinationDir }),
  )
}
