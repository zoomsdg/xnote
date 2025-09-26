# XNote - 简易多媒体记事本

基于 Android 的简易记事本应用，支持富文本编辑、图片插入和音频录制。

## 功能特性

### ✅ 已实现功能

1. **富文本编辑**
   - 连续的文本编辑体验
   - 文本、图片、音频在同一编辑区域
   - 支持全选、复制等原生操作

2. **多媒体支持**
   - 📷 图片插入（相册选择、拍照）
   - 🎤 音频录制和播放
   - 内嵌式显示，保持编辑流畅性

3. **数据管理**
   - 块状数据存储（支持扩展）
   - Room 数据库本地存储
   - 自动保存功能

4. **用户界面**
   - Material Design 设计风格
   - 记事列表和详情页面
   - 直观的多媒体操作界面

## 技术架构

### 数据层
- **Room 数据库**: 本地数据持久化
- **块状存储**: 灵活的内容存储结构
- **Repository 模式**: 数据访问抽象层

### UI层
- **MVVM 架构**: ViewModel + LiveData/Flow
- **自定义 RichEditText**: 基于 Spannable 的富文本编辑器
- **ViewBinding**: 类型安全的视图绑定

### 功能模块
- **AudioRecorder/AudioPlayer**: 音频录制播放
- **ImageUtils**: 图片处理工具
- **PermissionUtils**: 权限管理

## 项目结构

```
app/src/main/java/com/example/xnote/
├── data/                   # 数据模型和数据库
│   ├── Note.kt
│   ├── NoteBlock.kt
│   ├── NoteDao.kt
│   ├── NoteDatabase.kt
│   └── Converters.kt
├── repository/             # 数据仓库
│   └── NoteRepository.kt
├── ui/                     # 自定义UI组件
│   └── RichEditText.kt
├── audio/                  # 音频功能
│   ├── AudioRecorder.kt
│   └── AudioPlayer.kt
├── utils/                  # 工具类
│   ├── FileUtils.kt
│   ├── ImageUtils.kt
│   └── PermissionUtils.kt
├── viewmodel/              # ViewModel
│   ├── MainViewModel.kt
│   └── NoteEditViewModel.kt
├── adapter/                # 适配器
│   └── NoteAdapter.kt
├── MainActivity.kt         # 主界面
└── NoteEditActivity.kt     # 编辑界面
```

## 数据模型

### 记事 (Note)
```kotlin
data class Note(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val version: Int
)
```

### 内容块 (NoteBlock)
```kotlin
data class NoteBlock(
    val id: String,
    val noteId: String,
    val type: BlockType,  // TEXT, IMAGE, AUDIO
    val order: Int,
    val text: String?,    // 文本内容
    val url: String?,     // 媒体文件路径
    val alt: String?,     // 图片描述
    val duration: Long?,  // 音频时长
    val width: Int?,      // 图片宽度
    val height: Int?      // 图片高度
)
```

## 核心功能实现

### 富文本编辑器
基于 Android EditText + Spannable 实现：
- 使用 `ReplacementSpan` 自定义媒体占位符
- 支持媒体块的插入、删除、移动
- 保持原生的文本编辑体验

### 块状存储转换
编辑器内容与数据库存储之间的转换：
```kotlin
// 编辑器 -> 数据块
fun toBlocks(): List<NoteBlock>

// 数据块 -> 编辑器
fun loadFromBlocks(blocks: List<NoteBlock>)
```

### 权限管理
动态申请必要权限：
- `RECORD_AUDIO`: 录音权限
- `READ_EXTERNAL_STORAGE`: 读取存储权限
- `CAMERA`: 相机权限

## 使用说明

### 基本操作
1. **创建记事**: 点击主界面右下角的"+"按钮
2. **编辑记事**: 在记事列表中点击任意记事
3. **保存记事**: 编辑时自动保存，也可手动点击保存按钮

### 插入多媒体
1. **插入图片**: 点击工具栏相机图标，选择"相册选择"或"拍照"
2. **录制音频**: 点击工具栏麦克风图标开始录音，再次点击停止

### 播放音频
点击编辑器中的音频占位符即可播放/暂停音频。

## 技术亮点

1. **统一编辑体验**: 文本和多媒体在同一编辑区域，保持连续的编辑流
2. **块状数据模型**: 灵活的存储结构，便于后续功能扩展
3. **自定义 Span**: 实现了媒体内容的内嵌显示
4. **MVVM 架构**: 清晰的数据流和状态管理
5. **协程异步**: 使用 Kotlin 协程处理数据库和文件操作

## 未来增强 (暂未实现)

- 录音转写 (ASR)
- 图片压缩和懒加载
- 协同编辑功能
- 云端同步
- 模板和标签系统
- 搜索功能
- 导出功能 (Markdown, HTML)

## 构建和运行

1. 确保 Android Studio 和 SDK 环境已配置
2. 克隆项目到本地
3. 使用 Android Studio 打开项目
4. 连接设备或启动模拟器
5. 运行应用

### 最低要求
- Android API 24 (Android 7.0)
- 支持音频录制和相机功能的设备

## 许可证

本项目仅用于学习和演示目的。