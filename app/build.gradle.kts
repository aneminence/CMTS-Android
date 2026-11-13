import java.util.Properties
plugins { id("com.android.application"); id("org.jetbrains.kotlin.plugin.compose") }
val localConfig = Properties().apply { rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) } }
android {
    namespace = "com.cmts.mobile"
    compileSdk = 35
    defaultConfig { applicationId = "com.cmts.mobile"; minSdk = 26; targetSdk = 35; versionCode = 6; versionName = "0.1.5"; manifestPlaceholders["AMAP_API_KEY"] = localConfig.getProperty("AMAP_API_KEY", "") }
    // AGP 9 debug defaults to v2-only; file-manager sideload on some OEMs needs v1 as well.
    signingConfigs {
        getByName("debug") {
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }
    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    buildFeatures { compose = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies {
    implementation("com.google.zxing:core:3.5.3")
    implementation("androidx.graphics:graphics-path:1.1.0")
    implementation("com.amap.api:3dmap-location-search:11.3.100_loc11.3.101_sea9.8.1")
    implementation(files("libs/CH34XUartDriver.jar"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.material3:material3:1.4.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    testImplementation("junit:junit:4.13.2")
    testImplementation(files("libs/json-20240303.jar"))
}
