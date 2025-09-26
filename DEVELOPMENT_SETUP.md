# XNote 开发环境配置与部署指南

## 🚀 快速开始

### 前置要求检查

在开始之前，请确保系统满足以下要求：

```bash
# 检查 Java 版本 (需要 OpenJDK 17+)
java -version

# 检查可用内存 (推荐 16GB+)
free -h

# 检查可用存储空间 (需要 10GB+)
df -h
```

## 🛠️ 开发环境配置

### 1. Java 开发环境安装

#### Ubuntu/Debian
```bash
# 安装 OpenJDK 17
sudo apt update
sudo apt install openjdk-17-jdk

# 验证安装
java -version
javac -version

# 配置 JAVA_HOME
echo 'export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64' >> ~/.bashrc
echo 'export PATH=$JAVA_HOME/bin:$PATH' >> ~/.bashrc
source ~/.bashrc
```

#### Windows
```powershell
# 下载并安装 OpenJDK 17
# https://adoptium.net/temurin/releases/

# 设置环境变量
setx JAVA_HOME "C:\Program Files\Eclipse Adoptium\jdk-17.0.16.101-hotspot"
setx PATH "%JAVA_HOME%\bin;%PATH%"
```

#### macOS
```bash
# 使用 Homebrew 安装
brew install openjdk@17

# 配置环境变量
echo 'export JAVA_HOME=$(/usr/libexec/java_home -v17)' >> ~/.zshrc
source ~/.zshrc
```

### 2. Android Studio 配置

#### 下载安装
1. 访问 [Android Studio 官网](https://developer.android.com/studio)
2. 下载 Android Studio 2022.3+ (Giraffe)
3. 按照安装向导完成安装

#### SDK 配置
```bash
# SDK 路径配置 (在 Android Studio 中)
SDK Location: ~/Android/Sdk (Linux/macOS)
SDK Location: C:\Users\%USERNAME%\AppData\Local\Android\Sdk (Windows)

# 必需的 SDK 组件
- Android SDK Platform 33 (Android 13)
- Android SDK Build-Tools 33.0.0
- Android SDK Platform-Tools 33.0.3
- Android SDK Tools (latest)
```

#### 环境变量配置
```bash
# Linux/macOS
export ANDROID_HOME=~/Android/Sdk
export ANDROID_SDK_ROOT=$ANDROID_HOME
export PATH=$ANDROID_HOME/cmdline-tools/latest/bin:$PATH
export PATH=$ANDROID_HOME/platform-tools:$PATH
export PATH=$ANDROID_HOME/emulator:$PATH

# Windows
setx ANDROID_HOME "C:\Users\%USERNAME%\AppData\Local\Android\Sdk"
setx PATH "%ANDROID_HOME%\cmdline-tools\latest\bin;%ANDROID_HOME%\platform-tools;%PATH%"
```

### 3. Gradle 配置

#### 系统级 Gradle 安装 (可选)
```bash
# Linux (使用 SDKMAN)
curl -s "https://get.sdkman.io" | bash
source ~/.bashrc
sdk install gradle 7.6

# Windows (使用 Chocolatey)
choco install gradle --version=7.6

# macOS (使用 Homebrew)
brew install gradle@7.6
brew link gradle@7.6
```

#### Gradle 配置优化
```bash
# ~/.gradle/gradle.properties
org.gradle.jvmargs=-Xmx4096m -XX:MaxMetaspaceSize=512m
org.gradle.parallel=true
org.gradle.caching=true
org.gradle.daemon=true
android.useAndroidX=true
android.enableJetifier=true
```

## 📦 项目环境搭建

### 1. 克隆项目
```bash
# 克隆项目到本地
git clone <project-repository-url>
cd xnote-project

# 检查项目结构
tree -L 3
```

### 2. 依赖安装与配置

#### Gradle Wrapper 验证
```bash
# 验证 Gradle Wrapper
./gradlew --version

# 清理项目
./gradlew clean
```

#### 依赖下载
```bash
# 下载所有依赖
./gradlew build --refresh-dependencies

# 查看依赖树
./gradlew app:dependencies
```

### 3. 项目配置验证

#### 编译检查
```bash
# Kotlin 编译检查
./gradlew compileDebugKotlin

# Java 编译检查 (如有)
./gradlew compileDebugJavaWithJavac

# 资源编译检查
./gradlew processDebugResources
```

#### 代码质量检查
```bash
# Lint 检查
./gradlew lint

# 单元测试
./gradlew test

# 代码覆盖率 (如果配置)
./gradlew jacocoTestReport
```

## 🔧 IDE 配置优化

### Android Studio 配置

#### 性能优化
```
File → Settings → Appearance & Behavior → System Settings
- Memory Settings: Heap Size 4096 MB
- Updates: Check for updates automatically

File → Settings → Build → Compiler
- Build process heap size: 4096 MB
- Parallel compilation: Enable
```

#### 代码风格配置
```
File → Settings → Editor → Code Style → Kotlin
- Use default Kotlin style guide

File → Settings → Editor → Inspections
- Enable Kotlin inspections
- Enable Android inspections
```

#### 插件推荐
```
- Kotlin Multiplatform Mobile
- Android APK Support
- Database Navigator
- GitToolBox
- SonarLint
```

## 📱 设备配置

### 1. Android 虚拟设备 (AVD) 配置

#### 创建推荐 AVD
```
Device: Pixel 6 Pro
System Image: Android 13 (API 33) x86_64
RAM: 4096 MB
VM Heap: 512 MB
Internal Storage: 8 GB
SD Card: 1 GB
```

#### AVD 性能优化
```bash
# 启用硬件加速
Hardware → Graphics: Hardware - GLES 2.0
Hardware → Multi-Core CPU: 4 cores (based on host)

# 内存配置
Advanced Settings → RAM: 4096 MB
Advanced Settings → VM Heap: 512 MB
```

### 2. 物理设备配置

#### 开发者选项启用
```
设置 → 关于手机 → 连续点击"版本号" 7次
设置 → 开发者选项 → 启用以下选项：
- USB 调试
- 保持唤醒状态
- 强制启用 2x MSAA (可选)
```

#### ADB 连接验证
```bash
# 检查设备连接
adb devices

# 安装调试版本
adb install app/build/outputs/apk/debug/app-debug.apk

# 查看日志
adb logcat -s "XNoteApp"
```

## 🚀 构建与部署

### 1. 开发构建

#### 调试版本构建
```bash
# 构建调试 APK
./gradlew assembleDebug

# 输出位置
ls -la app/build/outputs/apk/debug/

# 安装到设备
./gradlew installDebug

# 构建并安装
./gradlew installDebug
```

#### 增量构建优化
```bash
# 仅构建变更部分
./gradlew assembleDebug --parallel

# 使用构建缓存
./gradlew assembleDebug --build-cache

# 离线构建
./gradlew assembleDebug --offline
```

### 2. 发布构建

#### 签名密钥生成
```bash
# 生成发布密钥
keytool -genkey -v -keystore release-key.keystore \
        -alias xnote-key -keyalg RSA -keysize 2048 \
        -validity 10000

# 密钥信息记录
Keystore: release-key.keystore
Alias: xnote-key
Password: [请安全保存]
```

#### 签名配置
```gradle
// app/build.gradle
android {
    signingConfigs {
        release {
            storeFile file('release-key.keystore')
            storePassword 'your-keystore-password'
            keyAlias 'xnote-key'
            keyPassword 'your-key-password'
        }
    }
    
    buildTypes {
        release {
            signingConfig signingConfigs.release
            minifyEnabled true
            proguardFiles getDefaultProguardFile('proguard-android-optimize.txt'), 
                         'proguard-rules.pro'
        }
    }
}
```

#### 发布版本构建
```bash
# 构建发布 APK
./gradlew assembleRelease

# 构建 AAB (推荐用于 Play Store)
./gradlew bundleRelease

# 输出文件
ls -la app/build/outputs/apk/release/
ls -la app/build/outputs/bundle/release/
```

### 3. 版本管理

#### 版本号管理
```gradle
// app/build.gradle
android {
    defaultConfig {
        versionCode 1      // 内部版本号，每次发布递增
        versionName "1.0"  // 用户可见版本号
    }
}
```

#### Git 标签管理
```bash
# 创建版本标签
git tag -a v1.0.0 -m "Release version 1.0.0"

# 推送标签
git push origin v1.0.0

# 查看所有标签
git tag -l
```

## 🧪 测试与质量保证

### 1. 单元测试

#### 运行测试
```bash
# 运行所有单元测试
./gradlew test

# 运行特定模块测试
./gradlew app:testDebugUnitTest

# 测试报告位置
open app/build/reports/tests/testDebugUnitTest/index.html
```

#### 测试覆盖率
```bash
# 生成覆盖率报告 (需要配置)
./gradlew jacocoTestReport

# 查看覆盖率报告
open app/build/reports/jacoco/jacocoTestReport/html/index.html
```

### 2. UI 测试

#### Instrumented 测试
```bash
# 运行 UI 测试
./gradlew connectedAndroidTest

# 测试报告
open app/build/reports/androidTests/connected/index.html
```

#### 测试分片 (大型项目)
```bash
# 分片运行测试
./gradlew connectedAndroidTest \
          -Pandroid.testInstrumentationRunnerArguments.numShards=4 \
          -Pandroid.testInstrumentationRunnerArguments.shardIndex=0
```

### 3. 性能测试

#### 内存泄漏检测
```bash
# 使用 LeakCanary (已在 debug 版本中集成)
# 运行应用并检查 Logcat 中的内存泄漏报告
adb logcat -s "LeakCanary"
```

#### 性能分析
```bash
# GPU 渲染分析
adb shell setprop debug.hwui.profile visual_bars

# 网络流量分析
adb shell dumpsys netstats detail

# 电量使用分析
adb shell dumpsys batterystats --reset
```

## 🔍 问题排查

### 1. 常见构建问题

#### Gradle 同步失败
```bash
# 清理并重新构建
./gradlew clean
./gradlew build --refresh-dependencies

# 清理 Gradle 缓存
rm -rf ~/.gradle/caches/

# Android Studio 缓存清理
File → Invalidate Caches and Restart
```

#### 依赖冲突
```bash
# 查看依赖冲突
./gradlew app:dependencies | grep "conflict"

# 强制使用特定版本
configurations.all {
    resolutionStrategy {
        force 'androidx.core:core-ktx:1.9.0'
    }
}
```

### 2. 运行时问题

#### 应用崩溃分析
```bash
# 获取崩溃日志
adb logcat -v time -s "AndroidRuntime"

# 导出崩溃报告
adb bugreport crash_report.zip
```

#### 权限问题
```bash
# 检查权限状态
adb shell dumpsys package com.example.xnote | grep permission

# 手动授权 (测试用)
adb shell pm grant com.example.xnote android.permission.RECORD_AUDIO
adb shell pm grant com.example.xnote android.permission.WRITE_EXTERNAL_STORAGE
```

### 3. 设备兼容性问题

#### 不同 API 级别测试
```bash
# API 24 (最低支持版本)
./gradlew connectedCheck -PminSdkVersion=24

# API 33 (目标版本)
./gradlew connectedCheck -PtargetSdkVersion=33
```

#### 架构兼容性
```bash
# 构建多架构版本
android {
    splits {
        abi {
            enable true
            reset()
            include 'x86', 'x86_64', 'arm64-v8a', 'armeabi-v7a'
            universalApk true
        }
    }
}
```

## 📋 开发工作流

### 1. 日常开发流程

```bash
# 1. 拉取最新代码
git pull origin main

# 2. 创建功能分支
git checkout -b feature/new-feature

# 3. 开发与测试
./gradlew compileDebugKotlin
./gradlew test

# 4. 代码质量检查
./gradlew lint

# 5. 提交代码
git add .
git commit -m "Add new feature: description"
git push origin feature/new-feature
```

### 2. 持续集成配置

#### GitHub Actions 示例
```yaml
# .github/workflows/android.yml
name: Android CI

on:
  push:
    branches: [ main, develop ]
  pull_request:
    branches: [ main ]

jobs:
  test:
    runs-on: ubuntu-latest
    
    steps:
    - uses: actions/checkout@v3
    
    - name: Set up JDK 17
      uses: actions/setup-java@v3
      with:
        java-version: '17'
        distribution: 'temurin'
        
    - name: Grant execute permission for gradlew
      run: chmod +x gradlew
      
    - name: Run tests
      run: ./gradlew test
      
    - name: Run lint
      run: ./gradlew lint
```

### 3. 代码审查检查清单

```
□ 代码符合项目编码规范
□ 所有新功能有对应的测试
□ Lint 检查通过无警告
□ 性能影响已评估
□ 安全风险已考虑
□ UI/UX 符合设计要求
□ 向后兼容性已验证
□ 文档已更新
```

## 🎯 性能优化建议

### 1. 构建性能优化

```gradle
// gradle.properties
org.gradle.jvmargs=-Xmx8192m -XX:MaxMetaspaceSize=1024m
org.gradle.parallel=true
org.gradle.caching=true
org.gradle.configureondemand=true
org.gradle.daemon=true

// 启用构建分析
--profile --build-cache --parallel
```

### 2. 应用性能优化

```
- 启用 ViewBinding 替代 findViewById
- 使用 RecyclerView 的 ViewHolder 模式
- 图片懒加载和缓存策略
- 数据库查询优化
- 内存泄漏预防
```

### 3. 发布优化

```gradle
android {
    buildTypes {
        release {
            minifyEnabled true
            shrinkResources true
            proguardFiles getDefaultProguardFile('proguard-android-optimize.txt')
        }
    }
}
```

---

## 📞 技术支持

### 问题报告
- **项目 Issue**: [GitHub Issues](https://github.com/your-org/xnote/issues)
- **安全问题**: security@yourcompany.com
- **功能建议**: feature-request@yourcompany.com

### 开发资源
- **Android 官方文档**: https://developer.android.com/docs
- **Kotlin 官方文档**: https://kotlinlang.org/docs/
- **Material Design**: https://material.io/design

---

*本开发指南涵盖了 XNote 项目的完整开发环境配置和部署流程，建议开发团队严格按照指南执行。*