plugins {
	id("com.android.application")
}

android {
	namespace = "dev.dunehd.jellyfinbridge"
	compileSdk = 36

	defaultConfig {
		applicationId = "dev.dunehd.jellyfinbridge"
		minSdk = 21
		targetSdk = 36
		versionCode = 1
		versionName = "0.1.0"
	}

	buildTypes {
		release {
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
