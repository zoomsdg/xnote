# XNote 项目构建环境说明

## 🛠️ 开发环境要求

### 系统要求
- **操作系统**: Windows 10+, macOS 10.14+, Ubuntu 18.04+ 或其他 Linux 发行版
- **内存**: 8GB RAM 最低，16GB RAM 推荐
- **存储**: 至少 10GB 可用空间
- **网络**: 稳定的互联网连接（用于下载依赖）

### 核心工具版本

#### Java 开发环境
```bash
# Java 版本要求
Java: OpenJDK 17.0.16+
JVM: OpenJDK 64-Bit Server VM
Provider: Ubuntu/Eclipse Temurin
Target: JVM 1.8 (兼容性)
```

#### Android 开发工具
```bash
# Android Studio
Android Studio: 2022.3+ (Giraffe)
Build Tools: 33.0.0
Platform Tools: 33.0.3
```

#### Gradle 构建系统
```bash
# Gradle 配置
Gradle: 7.6
Gradle Plugin: 7.4.0
Kotlin: 1.7.10
Groovy: 3.0.13
Apache Ant: 1.10.11
```

#### Android SDK 配置
```bash
# SDK 版本
Compile SDK: 33 (Android 13)
Target SDK: 33 (Android 13)
Min SDK: 24 (Android 7.0)
Build Tools: 33.0.0
```

## 📦 项目依赖详单

### 核心 Android 依赖
```gradle
// Android 核心库
androidx.core:core-ktx:1.9.0
androidx.appcompat:appcompat:1.6.1
com.google.android.material:material:1.8.0
androidx.constraintlayout:constraintlayout:2.1.4
androidx.recyclerview:recyclerview:1.3.0
```

### 架构组件依赖
```gradle
// MVVM 架构组件
androidx.lifecycle:lifecycle-viewmodel-ktx:2.6.2
androidx.lifecycle:lifecycle-livedata-ktx:2.6.2
androidx.activity:activity-ktx:1.7.2
androidx.fragment:fragment-ktx:1.6.1
```

### 数据存储依赖
```gradle
// Room 数据库
androidx.room:room-runtime:2.5.0
androidx.room:room-ktx:2.5.0
androidx.room:room-compiler:2.5.0 (kapt)

// JSON 序列化
com.google.code.gson:gson:2.10.1
```

### 多媒体处理依赖
```gradle
// 图片加载
com.github.bumptech.glide:glide:4.14.2

// 音频播放
androidx.media3:media3-exoplayer:1.0.2
androidx.media3:media3-common:1.0.2
```

### 文件处理依赖
```gradle
// ZIP 文件处理
net.lingala.zip4j:zip4j:2.11.5

// 安全加密
androidx.security:security-crypto:1.1.0-alpha06
```

### 测试依赖
```gradle
// 单元测试
junit:junit:4.13.2

// Android 测试
androidx.test.ext:junit:1.1.5
androidx.test.espresso:espresso-core:3.5.1
```

## 🔧 构建配置

### Gradle 配置文件

#### 项目级 build.gradle
```gradle
buildscript {
    ext.kotlin_version = '1.7.10'
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath 'com.android.tools.build:gradle:7.4.0'
        classpath "org.jetbrains.kotlin:kotlin-gradle-plugin:$kotlin_version"
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
    }
}
```

#### 应用级 build.gradle
```gradle
plugins {
    id 'com.android.application'
    id 'org.jetbrains.kotlin.android'
    id 'kotlin-kapt'
}

android {
    namespace 'com.example.xnote'
    compileSdk 33
    
    defaultConfig {
        applicationId "com.example.xnote"
        minSdk 24
        targetSdk 33
        versionCode 1
        versionName "1.0"
        testInstrumentationRunner "androidx.test.runner.AndroidJUnitRunner"
    }
    
    buildTypes {
        release {
            minifyEnabled false
            proguardFiles getDefaultProguardFile('proguard-android-optimize.txt'), 'proguard-rules.pro'
        }
    }
    
    compileOptions {
        sourceCompatibility JavaVersion.VERSION_1_8
        targetCompatibility JavaVersion.VERSION_1_8
    }
    
    kotlinOptions {
        jvmTarget = '1.8'
    }
    
    buildFeatures {
        viewBinding true
    }
}
```

## 🚀 构建命令

### 开发构建
```bash
# 调试版本构建
./gradlew assembleDebug

# 清理构建
./gradlew clean

# 运行测试
./gradlew test

# 安装调试版本
./gradlew installDebug
```

### 发布构建
```bash
# 发布版本构建
./gradlew assembleRelease

# 生成签名 APK
./gradlew bundleRelease

# 检查依赖
./gradlew dependencies
```

### 代码质量检查
```bash
# Lint 检查
./gradlew lint

# Kotlin 编译检查
./gradlew compileDebugKotlin
```

## 📱 设备兼容性

### Android 版本支持
```
Android 7.0 (API 24) - 最低版本
Android 7.1 (API 25) - 支持
Android 8.0 (API 26) - 支持
Android 8.1 (API 27) - 支持
Android 9.0 (API 28) - 支持
Android 10 (API 29) - 支持
Android 11 (API 30) - 支持
Android 12 (API 31) - 支持
Android 13 (API 33) - 目标版本
```

### 设备架构支持
- **ARM64-v8a**: 主要支持（64位ARM）
- **ARM-v7a**: 兼容支持（32位ARM）
- **x86_64**: 模拟器支持
- **x86**: 模拟器兼容

## 📋 版本兼容性矩阵

| 组件 | 最低版本 | 推荐版本 | 最新测试版本 |
|------|----------|----------|--------------|
| Java | 8 | 17 | 17 |
| Gradle | 7.4 | 7.6 | 8.0 |
| Android Studio | 2022.1 | 2022.3 | 2023.1 |
| Kotlin | 1.7.0 | 1.7.10 | 1.8.0 |
| Android SDK | 24 | 33 | 34 |

---

*本文档随项目更新而持续维护，最后更新: 2024年*