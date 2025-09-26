实现“分享目标”或“分享接收器”。本应用需要声明它能够处理其他应用发出的 ACTION_SEND 或 ACTION_SEND_MULTIPLE 等“分享”意图（Intent）。

以下是在 Android 和 iOS 两大平台上实现这一功能的详细步骤和核心概念。
除此文本与图片两种外，还增加链接的分享处理，处理都是以接受的文本/（多张）图片/链接为内容新建记事。


Android 平台
在 Android 中，你需要在应用的 AndroidManifest.xml 文件中注册一个 <intent-filter>，并指定相应的 Activity 来处理分享过来的数据。

核心步骤：
在 AndroidManifest.xml 中声明 Intent Filter
你需要为你希望处理分享操作的 Activity（例如 ShareTargetActivity）添加一个 <intent-filter>。

xml
<activity
    android:name=".ShareTargetActivity"
    android:label="@string/app_name"
    android:theme="@style/Theme.AppCompat.Light">
    <!-- 过滤器声明此Activity可以接收分享的数据 -->
    <intent-filter>
        <!-- 处理 ACTION_SEND 意图 -->
        <action android:name="android.intent.action.SEND" />
        <!-- 指定数据类型，这里以文本和图片为例 -->
        <category android:name="android.intent.category.DEFAULT" />
        <!-- 数据类型：纯文本 -->
        <data android:mimeType="text/plain" />
        <!-- 数据类型：任何类型的图片 -->
        <data android:mimeType="image/*" />
    </intent-filter>

    <!-- 可选：处理多内容分享 (ACTION_SEND_MULTIPLE) -->
    <intent-filter>
        <action android:name="android.intent.action.SEND_MULTIPLE" />
        <category android:name="android.intent.category.DEFAULT" />
        <data android:mimeType="image/*" />
    </intent-filter>
</activity>
关键属性解释：

<action android:name="android.intent.action.SEND" />： 表示该 Activity 可以处理发送单个内容的分享。

<action android:name="android.intent.action.SEND_MULTIPLE" />： 表示可以处理发送多个内容（如多张图片）的分享。

<category android:name="android.intent.category.DEFAULT" />： 必须添加，否则你的 Activity 不会被系统识别为可以处理该 Intent。

<data android:mimeType="..." />： 定义你的应用可以接收的数据类型。常见的 MIME 类型有：

text/plain： 纯文本

image/*： 任何类型的图片（image/jpeg, image/png 等）

除此文本与图片两种外，还增加链接的分享处理，处理都是以接受的文本/（多张）图片/链接为内容新建记事。
你可以添加多个 <data> 标签来支持多种类型。

在目标 Activity 中处理接收到的数据
在 ShareTargetActivity 的 onCreate 方法中，你需要获取并处理分享过来的 Intent。

java
// Kotlin 示例 (在 ShareTargetActivity.kt 中)
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_share_target)

    // 获取意图
    val intent = intent
    val action = intent.action
    val type = intent.type

    // 判断是否是分享意图
    if (action == Intent.ACTION_SEND && type != null) {
        when (type) {
            "text/plain" -> handleSharedText(intent)
            "image/*", "image/jpeg", "image/png" -> handleSharedImage(intent)
            // 处理其他支持的MIME类型...
        }
    } else if (action == Intent.ACTION_SEND_MULTIPLE && type != null) {
        // 处理多项目分享
        handleSharedMultipleImages(intent)
    } else {
        // 正常启动Activity，不是来自分享
        finish() // 如果不是分享意图，可以直接关闭
    }
}

private fun handleSharedText(intent: Intent) {
    // 从Intent中提取文本
    val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
    val sharedSubject = intent.getStringExtra(Intent.EXTRA_SUBJECT) // 主题（可选）
    
    if (sharedText != null) {
        // 更新UI，将 sharedText 显示或处理
        textView.text = sharedText
    }
}

private fun handleSharedImage(intent: Intent) {
    // 从Intent中提取图片Uri
    val imageUri: Uri? = intent.getParcelableExtra<Parcelable>(Intent.EXTRA_STREAM) as? Uri
    // 或者使用ClipData（某些应用会这样分享）
    // val clipData = intent.clipData
    
    if (imageUri != null) {
        // 使用Uri加载图片，例如使用Glide、Picasso或通过ContentResolver
        // Glide.with(this).load(imageUri).into(imageView)
    }
}

private fun handleSharedMultipleImages(intent: Intent) {
    val imageUris = ArrayList<Uri>()
    // 获取Uri列表
    if (intent.clipData != null) {
        val clipData = intent.clipData
        for (i in 0 until clipData.itemCount) {
            val item = clipData.getItemAt(i)
            val uri = item.uri
            uri?.let { imageUris.add(it) }
        }
    } 
    // 也可以从EXTRA_STREAM获取（某些旧应用可能用这种方式）
    // val uris = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
    
    // 处理Uri列表
    if (imageUris.isNotEmpty()) {
        // 加载多张图片...
    }
}
#完成代码后，安装你的应用。然后从任何一个可以分享内容的App（如浏览器、图库、文件管理器）尝试分享文本或图片，你的应用就应该出现在系统的分享菜单列表中了。







iOS 平台


在 iOS 中，你需要通过修改项目的 Info.plist 文件来声明你的应用支持哪些类型的分享扩展。

核心步骤：
在 Info.plist 中声明扩展点
打开你的项目，找到 Info.plist 文件。你需要添加一个 NSExtension 字典来定义你的分享目标。

最简单的方法（Xcode）：

右键点击 Info.plist -> Open As -> Source Code。

将以下 XML 代码片段粘贴到 <dict> 主标签内。

或者使用 Property List 编辑器：
添加以下键值对（Key-Value）。

xml
<!-- 在 Info.plist 的 <dict> 标签内添加 -->
<key>CFBundleDocumentTypes</key>
<array>
  <dict>
    <key>CFBundleTypeName</key>
    <string>Text</string>
    <key>LSItemContentTypes</key>
    <array>
      <string>public.plain-text</string>
    </array>
  </dict>
  <dict>
    <key>CFBundleTypeName</key>
    <string>Images</string>
    <key>LSItemContentTypes</key>
    <array>
      <string>public.image</string>
    </array>
  </dict>
  <dict>
    <key>CFBundleTypeName</key>
    <string>URL</string>
    <key>LSItemContentTypes</key>
    <array>
      <string>public.url</string>
    </array>
  </dict>
</array>

<key>NSExtension</key>
<dict>
  <key>NSExtensionAttributes</key>
  <dict>
    <key>NSExtensionActivationRule</key>
    <string>TRUEPREDICATE</string> <!-- 最简单的规则，接受任何类型 -->
  </dict>
  <key>NSExtensionPointIdentifier</key>
  <string>com.apple.share-services</string>
  <key>NSExtensionPrincipalClass</key>
  <string>$(PRODUCT_MODULE_NAME).ShareExtensionViewController</string> <!-- 你的扩展视图控制器类名 -->
</dict>
关键属性解释：

CFBundleDocumentTypes: 声明你的应用可以打开的文档类型。

NSExtensionPointIdentifier: 必须设置为 com.apple.share-services，表明这是一个分享扩展。

NSExtensionPrincipalClass: 指定处理分享请求的视图控制器类（例如 ShareExtensionViewController）。

NSExtensionActivationRule: 一个谓词，用于更精细地控制何时显示你的分享扩展。TRUEPREDICATE 表示总是显示。你可以在这里定义更复杂的规则，例如只接受特定类型的图片或链接。

创建分享扩展目标 (Share Extension)
更现代和推荐的做法是创建一个独立的 Share Extension 目标（Target），而不是直接在主应用的 Info.plist 中配置。

在 Xcode 中，菜单栏选择 File -> New -> Target...。

选择 iOS -> Application Extension -> Share Extension，然后点击 Next。

为扩展命名（例如 MyAppShare），然后点击 Finish。
Xcode 会自动为你创建这个新目标，并生成包含 SLComposeServiceViewController 子类的模板代码和配置好的 Info.plist。你只需要在这个自动生成的视图控制器里编写处理分享数据的逻辑。

在扩展视图控制器中处理数据
在自动生成的 ShareViewController.swift（或 Objective-C 文件）中，你需要重写 isContentValid、didSelectPost 等方法。

swift
// Swift 示例 (在 ShareViewController.swift 中)
import UIKit
import Social
import MobileCoreServices

class ShareViewController: SLComposeServiceViewController {

    private var sharedText: String?
    private var sharedImages: [UIImage] = []
    
    override func viewDidLoad() {
        super.viewDidLoad()
        
        // 从扩展上下文中获取分享的条目
        guard let extensionItems = extensionContext?.inputItems as? [NSExtensionItem] else {
            return
        }
        
        for item in extensionItems {
            // 遍历所有附件
            if let attachments = item.attachments {
                for attachment in attachments {
                    // 检查是否是纯文本
                    if attachment.hasItemConformingToTypeIdentifier(kUTTypePlainText as String) {
                        attachment.loadItem(forTypeIdentifier: kUTTypePlainText as String, options: nil) { [weak self] (data, error) in
                            if let text = data as? String {
                                DispatchQueue.main.async {
                                    self?.sharedText = text
                                }
                            }
                        }
                    }
                    // 检查是否是图片
                    if attachment.hasItemConformingToTypeIdentifier(kUTTypeImage as String) {
                        attachment.loadItem(forTypeIdentifier: kUTTypeImage as String, options: nil) { [weak self] (data, error) in
                            if let url = data as? URL, let image = UIImage(contentsOfFile: url.path) {
                                DispatchQueue.main.async {
                                    self?.sharedImages.append(image)
                                }
                            } else if let image = data as? UIImage {
                                DispatchQueue.main.async {
                                    self?.sharedImages.append(image)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override func didSelectPost() {
        // 用户点击了“Post”按钮
        // 在这里将 sharedText 和 sharedImages 传递给主App
        // 可以使用 App Groups 和 UserDefaults 或者共享的容器进行应用间数据传递
        
        // 例如，使用 UserDefaults 的 App Group
        if let sharedDefaults = UserDefaults(suiteName: "group.yourcompany.yourappgroup") {
            sharedDefaults.set(sharedText, forKey: "sharedText")
            // 注意：UIImage 不能直接存，需要转换为 Data 或保存到共享容器
        }
        
        // 通知扩展任务完成
        self.extensionContext!.completeRequest(returningItems: [], completionHandler: nil)
    }

    override func configurationItems() -> [Any]! {
        // 可以在这里配置扩展的选项（如配置分享目的地等），返回空数组则表示没有配置项
        return []
    }
}
与应用主程序通信
分享扩展是一个独立的进程，你需要使用 App Groups 来与主应用共享数据。

为你的主应用和分享扩展启用 App Groups（在 Signing & Capabilities 中为两个Target添加相同的App Group）。

在扩展中，使用 UserDefaults(suiteName: "group.yourcompany.yourappgroup") 将数据写入共享的 UserDefaults。

在主应用中，监听 UIApplication.didBecomeActiveNotification 通知，并从相同的 UserDefaults 套件中读取数据并进行处理。

测试：
在真机上运行你的应用（分享扩展在模拟器上可能无法完全正常工作）。然后从 Safari 或照片应用中选择分享，你的应用扩展就应该出现在分享菜单的底部（可能需要向左滑动并点击“更多”来启用它）。
