plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    `maven-publish`
}

android {
    namespace = "com.danielzn.liquidglass"
    // API 36：RuntimeColorFilter / RuntimeXfermode（AGSL 颜色滤镜与混合模式）
    compileSdk = 36
    // 锁定 NDK：不指定的话 CI 会用 runner 默认版本，编出的 .so 与本地不一致
    ndkVersion = "27.0.12077973"

    defaultConfig {
        minSdk = 24

        // compileOnly 依赖（appcompat / material / viewpager2）的 -dontwarn：使用方没引入它们、
        // 又开了 R8 时，引用它们的类（DialogBuilder / TabLayoutMediator）不会让构建报缺类
        consumerProguardFiles("consumer-rules.pro")

        // NDK 原生模糊/色差加速（CPU 管线）
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }

        // 16KB 页对齐（Android 16 设备的 ELF 对齐要求；NDK r28 起默认，r27 需显式开启）
        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    // JitPack / maven-publish：发布 release 变体并附带源码 jar
    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)

    // 只有 LiquidGlassDialogBuilder 用到 AlertDialog / MaterialAlertDialogBuilder。
    // compileOnly：编译期可见但不写进 POM，使用方不碰这个类就不会被强制拖进
    // appcompat + material（库其余部分保持只依赖 core-ktx）。
    // 要用玻璃弹窗的话，使用方自己引入这两个库即可。
    compileOnly(libs.androidx.appcompat)
    compileOnly(libs.material)

    // 只有 LiquidGlassTabLayoutMediator 用到 ViewPager2，同样 compileOnly：
    // 用它和 ViewPager2 联动的使用方本来就依赖 viewpager2
    compileOnly(libs.androidx.viewpager2)

    testImplementation(libs.junit)
}

// JitPack 发布配置：
// 使用方式（consumer 项目）：
//   repositories { maven { url = uri("https://jitpack.io") } }
//   dependencies { implementation("com.github.QWEA0:liquidglass:<tag>") }
afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = "com.github.daniel-zn"
                artifactId = "liquidglass"
                version = "2.0.11"
            }
        }
    }
}
