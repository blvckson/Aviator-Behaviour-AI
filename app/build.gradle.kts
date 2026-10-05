plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
 namespace="com.blvckson.aviatorbehaviour"
 compileSdk=34
 defaultConfig {
  applicationId="com.blvckson.aviatorbehaviour"
  minSdk=26
  targetSdk=28
  versionCode=2
  versionName="0.2"
 }
 compileOptions {
  sourceCompatibility=JavaVersion.VERSION_1_8
  targetCompatibility=JavaVersion.VERSION_1_8
 }
 kotlinOptions {
  jvmTarget = "1.8"
 }
}
dependencies {
 implementation("com.google.mlkit:text-recognition:16.0.1")
}
