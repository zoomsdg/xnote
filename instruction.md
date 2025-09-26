1. 基本概念

所有文字 在一个 大编辑区（富文本编辑器容器）里输入，用户感觉就是一个长文档。

图片/音频等多媒体 作为“特殊块”插入到光标所在处，就像输入文字时突然换成了一张图片。

这样 用户自然的拖拽全选 就能覆盖整个文档（文字、图片占位符），不会被割裂。

2. 用户体验效果
✍️编辑体验

就像 Word、微信长文、飞书文档一样：

光标流畅移动，可以从文字 A 一直拉到文字 Z。

选区会顺便覆盖图片/音频，占位符被算作一个字符。

📌 插入体验

在输入法光标处点击 “+” ，可插入：

图片：直接显示缩略图，可点开大图。

音频：显示一个小播放器（进度条 + 播放按钮）。

插入的多媒体是一个块级元素，但和文字一样在同一条“内容流”里。

📋 复制体验

全选复制 → 得到所有文字，图片/音频部分可以复制成特殊占位文本（例如 [图片]、[音频]）。

粘贴时再判断是否能还原（比如在同一个笔记应用里，可以恢复图片/音频；在微信里只能变成占位符）。

3. 数据结构（推荐）

存储时保持灵活，不用真把文字和多媒体混一起：

[
  {"type": "text", "data": "今天去跑步了..."},
  {"type": "image", "data": "img123.jpg"},
  {"type": "text", "data": "感觉很轻松"},
  {"type": "audio", "data": "audio456.mp3"},
  {"type": "text", "data": "总结：坚持锻炼真不错！"}
]

渲染时：

把所有 text 合并渲染到一个 富文本容器。

在对应位置把 image/audio 替换成 DOM 节点（卡片）。

这样既有利于 全选复制，也便于后续扩展。

4. 易于实现的技术

Web 端：用 Quill / TipTap / Draft.js 这种富文本框架，支持 block embed。

移动端（iOS/Android）：

iOS → UITextView + 自定义 attachment（NSTextAttachment）。

Android → EditText + span/自定义 view。

这些技术都已经有现成实践（微信长文、知乎回答编辑器、飞书文档）。

✅ 总结：
采用 文本连续渲染 + 多媒体内嵌块 的方案，以体验最接近主流笔记应用：

文本连续流畅编辑；

图片/音频内嵌；

原生全选复制正常；

数据层面依然是块状存储，扩展性强。


目标

体验目标：像飞书/Notion 一样，文本在一个连续编辑区中输入；图片、音频以内嵌块插入，保持原生全选/复制自然可用。

实现目标：工程上易维护、可扩展（后续可加入附件、待办、代码块、录音转写等）。

技术选型

Web 首选：ProseMirror 生态（推荐 TipTap）。理由：文档树模型强、可定义自定义 Node（图片/音频）、原生处理选区、复制/粘贴、序列化。

Android：EditText + Spannable（生产级更稳），或 Jetpack Compose + RichText 库（快速但成熟度略低）。

iOS：UITextView + NSTextAttachment（或 TextKit 2）。

存储后端：块数组（block list），多媒体文件走对象存储（OSS/S3/本地沙盒）。

数据模型（存储）

存储保持块化，渲染时合并到一个“连续文本流”中，并在指定位置插入多媒体节点。

{
  "id": "note_001",
  "title": "跑步记录",
  "blocks": [
    { "id": "b1", "type": "text",  "text": "今天去跑步了" },
    { "id": "b2", "type": "image", "url": "https://.../img123.jpg", "alt": "操场" },
    { "id": "b3", "type": "text",  "text": "感觉很轻松。" },
    { "id": "b4", "type": "audio", "url": "https://.../aud456.mp3", "duration": 37 }
  ],
  "createdAt": 1693212345000,
  "updatedAt": 1693213345000,
  "version": 3
}

文档→编辑器映射：

渲染时将 text 顺序拼接为一个富文本容器；在 b2/b4 的位置插入“原子块节点”。

编辑时新增/删除/移动节点同步回 blocks。







Web 端实现（TipTap / ProseMirror）
1) 初始化编辑器

依赖：@tiptap/core @tiptap/starter-kit @tiptap/extension-image。

开启 history、placeholder、dropcursor。

配置 editable: true，并把初始 blocks 转为 ProseMirror Node 数组。

伪代码

const editor = new Editor({
  extensions: [StarterKit.configure({
    // 保持段落、换行、历史等基础能力
  }), Image.configure({ inline: false, allowBase64: false }), AudioNode /* 自定义, 见下 */],
  content: blocksToProseMirror(blocks),
  onUpdate: ({ editor }) => debounceSave(pmToBlocks(editor.getJSON())),
});
2) 自定义音频内嵌块（AudioNode）

作为 块级、原子节点（atom: true, group: 'block'），不可被拆分，但可整体选中/删除/移动。

parseHTML / renderHTML 与 addNodeView 配置自定义 DOM（播放器 UI）。

核心要点

selectable: true：支持键盘左右移动越过音频块、Backspace 删除。

draggable: true：允许拖动重排（可选）。

简化示意

export const AudioNode = Node.create({
  name: 'audioBlock', group: 'block', atom: true, selectable: true, draggable: true,
  addAttributes() { return { src: { default: '' }, duration: { default: 0 } } },
  parseHTML() { return [{ tag: 'audio[data-block]'}] },
  renderHTML({ HTMLAttributes }) { return ['audio', { ...HTMLAttributes, 'data-block': 'audio', controls: '' }] },
  addNodeView() { return ({ node }) => {
    const dom = document.createElement('div'); dom.className = 'audio-block';
    const audio = document.createElement('audio'); audio.controls = true; audio.src = node.attrs.src; dom.appendChild(audio);
    return { dom };
  }},
  addCommands() { return {
    insertAudio: attrs => ({ commands }) => commands.insertContent({ type: this.name, attrs })
  }}
});
3) 全选/复制策略

原生全选：文档是一个连续树，选区可跨节点。

复制到纯文本：注册 clipboardTextSerializer（或在导出功能中），将图片/音频序列化为占位符：

图片：![alt](url)

音频：[audio: url duration=37s]

示例

EditorView.prototype.clipboardTextSerializer = (slice) => serializeToPlainText(slice.content);
4) 粘贴处理（可选增强）

从外部粘贴图片：拦截 paste 事件，若 DataTransfer.files 存在图片，走上传管线后插入 image 节点。

从外部粘贴音频链接：识别为音频节点。

5) 上传与文件管理

图片/音频选择：本地选择/拍照/录音后获得 Blob。

上传流程：

先插入“占位节点（uploading=true）”；

调用上传接口拿到 url；

用 setNodeAttribute 替换为正式 src=url，移除 uploading 状态；

失败则标红并提供重试。

6) 自动保存与离线

本地草稿：localStorage / IndexedDB + onUpdate 节流（~800ms）。

云端：文档 JSON + 附件 URL。失败重试、冲突用 updatedAt + 简单 merge，或引入 Yjs 做 CRDT（可选）。

7) 键盘与光标规则

使音频/图片节点为原子：

左右键可从两侧“跳过”节点。

Backspace 在节点前时删除该节点；在节点后时退格到上一个文本。

段落间回车创建新段落；在多媒体节点前后自动插入段落，保证焦点不丢失。

8) 拖拽/排序

开启 draggable，在 NodeView 外层加“拖拽把手”。

监听拖拽完成事件，更新 blocks 顺序（或依赖 PM 事务再反推 blocks）。

9) 导出

纯文本：序列化为文本 + 占位符。

Markdown：图片转 ![]()，音频转自定义语法 [audio](url "37s")。

HTML：使用 DOMSerializer 导出，音频节点保留 <audio controls src=...>。








Android 实现（EditText + Spannable）
1) 组件选择

EditText 搭配 Spannable + 自定义 ReplacementSpan/ImageSpan：

图片：ImageSpan（或 GlideImageSpan）。

音频：自定义 AudioSpan（绘制图标/波形 + 点击播放；或使用 Span 承载一个浮动小 View）。

2) 插入与选区

在当前 Selection 处插入换行与占位符字符（如 \uFFFC 对象替换符），再附着 Span。

原子性：给 Span 设置 SPAN_EXCLUSIVE_EXCLUSIVE，并在 InputFilter 中阻止把 Span 切开。

方向键移动时，按光标逻辑跳过 Span；Backspace 删除整个 Span。

3) 复制/粘贴

复制时拦截 ClipboardManager，将 Spannable 转为：

纯文本：用占位符 [图片] / [音频: url 37s]。

自定义 MIME（如 text/markdown）以支持在自己 App 内粘贴还原。

粘贴图片文件/URI 时，走上传→替换 Span 流程。

4) 录音与播放

录音：MediaRecorder（或 Jetpack Media3 录音能力库），保存到 app 私有目录。

播放：ExoPlayer/Media3；AudioSpan 内处理播放/暂停、进度回调，绘制进度条。

权限：RECORD_AUDIO，动态申请与错误提示。

5) 持久化

富文本保存为自定义 JSON（文本 + Span 元数据：起止索引、类型、url、duration）。

打开时重建 Spannable，重放 Span。










iOS 实现（UITextView + NSTextAttachment）
1) 插入

使用 NSTextAttachment 作为图片/音频的“对象替换字符”。

自定义 AttachmentCell（iOS 15+ 可用 NSTextAttachment 的 image/bounds 或 TextKit 2 自定义渲染）。

2) 选区与删除

光标移动自动把 Attachment 当作一个原子单元；Backspace 删除整个 Attachment。

3) 复制/粘贴

覆盖 paste: 与 copy:，将富文本 NSAttributedString 序列化：

纯文本：占位 [图片]、[音频: url 37s]。

RTF/自定义 UTType 以支持 App 内还原。

4) 录音与播放

录音：AVAudioRecorder；播放：AVAudioPlayer/AVPlayer。

自定义 AttachmentView 内放播放控件与时长/进度。

同步与文件

文件上传：统一走 upload(file) -> { url, width, height, duration }。

失败重试：节点标 status=uploading|error|ok，UI 可点“重试”。

离线：先本地缓存文件，恢复网络后补传并更新节点 URL。

测试用例（关键交互）

光标在图片/音频前后移动、Backspace/Delete 行为正确。

全选复制包含多媒体节点，导出的纯文文本/Markdown 正确。

从外部粘贴图片文件、音频链接，能正确识别与插入。

上传中断/失败时的 UI 与数据一致性。

多平台同文档的打开/导出一致性（HTML/MD）。


后续增强（可选）

	录音转写（ASR）并与音频节点联动。

	图片压缩与懒加载；音频波形预览。

	协同编辑（Yjs/ProseMirror collab）。

	模板/标签/提醒时间等记事能力扩展。

