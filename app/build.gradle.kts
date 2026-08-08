import java.io.File

plugins {
	id("com.android.application")
}

val releaseKeystorePath = providers.environmentVariable("ANDROID_RELEASE_KEYSTORE")
val releaseStorePassword = providers.environmentVariable("ANDROID_RELEASE_STORE_PASSWORD").orElse(
	providers.environmentVariable("ANDROID_RELEASE_STORE_PASSWORD_FILE")
		.map { File(it).readText().trim() }
)
val releaseKeyAlias = providers.environmentVariable("ANDROID_RELEASE_KEY_ALIAS")
val releaseKeyPassword = providers.environmentVariable("ANDROID_RELEASE_KEY_PASSWORD").orElse(
	providers.environmentVariable("ANDROID_RELEASE_KEY_PASSWORD_FILE")
		.map { File(it).readText().trim() }
)

android {
	namespace = "dev.dunehd.jellyfinbridge"
	compileSdk = 36

	defaultConfig {
		applicationId = "dev.dunehd.jellyfinbridge"
		minSdk = 21
		targetSdk = 36
		versionCode = 3
		versionName = "0.3.0"
	}

	signingConfigs {
		create("release") {
			storeFile = releaseKeystorePath.orNull?.let { file(it) }
			storePassword = releaseStorePassword.orNull
			keyAlias = releaseKeyAlias.orNull
			keyPassword = releaseKeyPassword.orNull
		}
	}

	buildTypes {
		release {
			signingConfig = signingConfigs.getByName("release")
			isMinifyEnabled = false
			proguardFiles(
				getDefaultProguardFile("proguard-android-optimize.txt"),
				"proguard-rules.pro",
			)
		}
	}

	compileOptions {
		sourceCompatibility = JavaVersion.VERSION_11
		targetCompatibility = JavaVersion.VERSION_11
	}
}

dependencies {
	testImplementation("junit:junit:4.13.2")
	testImplementation("org.json:json:20260719")
}

val validateReleaseSigning by tasks.registering {
	doLast {
		val requiredValues = mapOf(
			"ANDROID_RELEASE_KEYSTORE" to releaseKeystorePath,
			"ANDROID_RELEASE_STORE_PASSWORD" to releaseStorePassword,
			"ANDROID_RELEASE_KEY_ALIAS" to releaseKeyAlias,
			"ANDROID_RELEASE_KEY_PASSWORD" to releaseKeyPassword,
		)
		val missing = requiredValues.filterValues { !it.isPresent }.keys
		check(missing.isEmpty()) {
			"Missing release signing configuration: ${missing.joinToString()}"
		}
		check(File(releaseKeystorePath.get()).isFile) {
			"Release keystore does not exist: ${releaseKeystorePath.get()}"
		}
	}
}

tasks.configureEach {
	if (name == "packageRelease") dependsOn(validateReleaseSigning)
}

tasks.register("printVersionName") {
	doLast {
		println(android.defaultConfig.versionName)
	}
}
