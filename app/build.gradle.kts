plugins { id("com.android.application"); id("org.jetbrains.kotlin.android"); id("org.jetbrains.kotlin.plugin.compose"); id("maven-publish") }
android {
 namespace = "com.anonvox.bleexplorer"
 compileSdk = 35
 defaultConfig {
  applicationId = "com.anonvox.bleexplorer"
  minSdk = 23
  targetSdk = 35
  versionCode = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 3
  versionName = System.getenv("VERSION_NAME") ?: "1.3.0"
 }
 val releaseKeystore = System.getenv("KEYSTORE_PATH")
 signingConfigs {
  create("release") {
   if (!releaseKeystore.isNullOrBlank()) {
    storeFile = file(releaseKeystore)
    storePassword = System.getenv("KEYSTORE_PASSWORD")
    keyAlias = System.getenv("KEY_ALIAS")
    keyPassword = System.getenv("KEY_PASSWORD")
   }
  }
 }
 buildTypes {
  release {
   isMinifyEnabled = false
   if (!releaseKeystore.isNullOrBlank()) signingConfig = signingConfigs.getByName("release")
  }
 }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 kotlinOptions { jvmTarget = "17" }
 buildFeatures { compose = true }
}

afterEvaluate {
 publishing {
  publications {
   create<MavenPublication>("releaseApk") {
    groupId = "com.anonvox"
    artifactId = "ble-explorer"
    version = android.defaultConfig.versionName ?: "1.3.0"
    artifact(file("$buildDir/outputs/apk/release/app-release.apk")) {
     extension = "apk"
    }
    pom {
     name.set("BLE Explorer")
     description.set("Android Bluetooth discovery and authorized local-network device audit.")
     url.set("https://github.com/rath0darya/BLE")
     scm { url.set("https://github.com/rath0darya/BLE") }
    }
   }
  }
  repositories {
   maven {
    name = "GitHubPackages"
    url = uri("https://maven.pkg.github.com/rath0darya/BLE")
    credentials {
     username = System.getenv("GITHUB_ACTOR")
     password = System.getenv("GITHUB_TOKEN")
    }
   }
  }
 }
}

dependencies {
 implementation(platform("androidx.compose:compose-bom:2024.12.01"))
 implementation("androidx.activity:activity-compose:1.10.0")
 implementation("androidx.compose.ui:ui"); implementation("androidx.compose.ui:ui-tooling-preview")
 implementation("androidx.compose.foundation:foundation"); implementation("androidx.compose.material3:material3")
 implementation("androidx.compose.material:material-icons-extended"); implementation("androidx.core:core-ktx:1.15.0")
 debugImplementation("androidx.compose.ui:ui-tooling")
}
