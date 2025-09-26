package com.example.xnote.ui

import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import android.text.Editable
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.TextWatcher
import android.text.style.ImageSpan
import android.text.style.ReplacementSpan
import android.util.AttributeSet
import android.view.MotionEvent
import androidx.appcompat.widget.AppCompatEditText
import com.example.xnote.data.BlockType
import com.example.xnote.data.NoteBlock
import com.example.xnote.utils.SecurityLog
import java.util.*

/**
 * 富文本编辑器
 */
class RichEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.appcompat.R.attr.editTextStyle
) : AppCompatEditText(context, attrs, defStyleAttr) {
    
    private val blockMap = mutableMapOf<String, NoteBlock>()
    private var onContentChangedListener: ((List<NoteBlock>) -> Unit)? = null
    private var onMediaClickListener: ((NoteBlock) -> Unit)? = null
    
    companion object {
        private const val OBJ_REPLACEMENT_CHAR = '\uFFFC' // 对象替换字符
    }
    
    init {
        // 设置文本监听器
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                notifyContentChanged()
            }
        })
    }
    
    /**
     * 设置内容变更监听器
     */
    fun setOnContentChangedListener(listener: (List<NoteBlock>) -> Unit) {
        onContentChangedListener = listener
    }
    
    /**
     * 设置媒体点击监听器
     */
    fun setOnMediaClickListener(listener: (NoteBlock) -> Unit) {
        onMediaClickListener = listener
    }
    
    /**
     * 从块列表加载内容
     */
    fun loadFromBlocks(blocks: List<NoteBlock>) {
        blockMap.clear()
        val builder = SpannableStringBuilder()
        
        for (block in blocks) {
            when (block.type) {
                BlockType.TEXT -> {
                    builder.append(block.text ?: "")
                }
                BlockType.IMAGE -> {
                    insertImagePlaceholder(builder, block)
                }
                BlockType.AUDIO -> {
                    insertAudioPlaceholder(builder, block)
                }
            }
        }
        
        setText(builder)
    }
    
    /**
     * 转换为块列表
     */
    fun toBlocks(): List<NoteBlock> {
        val blocks = mutableListOf<NoteBlock>()
        val text = text ?: return blocks
        val spans = text.getSpans(0, text.length, MediaSpan::class.java)
        
        var currentPos = 0
        var order = 0
        
        for (span in spans.sortedBy { text.getSpanStart(it) }) {
            val spanStart = text.getSpanStart(span)
            
            // 添加span前的文本
            if (currentPos < spanStart) {
                val textContent = text.substring(currentPos, spanStart)
                if (textContent.isNotEmpty()) {
                    blocks.add(NoteBlock(
                        id = UUID.randomUUID().toString(),
                        noteId = "",
                        type = BlockType.TEXT,
                        order = order++,
                        text = textContent
                    ))
                }
            }
            
            // 添加媒体块
            blocks.add(span.block.copy(order = order++))
            currentPos = text.getSpanEnd(span)
        }
        
        // 添加剩余文本
        if (currentPos < text.length) {
            val textContent = text.substring(currentPos)
            if (textContent.isNotEmpty()) {
                blocks.add(NoteBlock(
                    id = UUID.randomUUID().toString(),
                    noteId = "",
                    type = BlockType.TEXT,
                    order = order,
                    text = textContent
                ))
            }
        }
        
        return blocks
    }
    
    /**
     * 在光标位置插入图片
     */
    fun insertImage(block: NoteBlock) {
        val start = selectionStart
        val builder = SpannableStringBuilder(text)
        
        insertImagePlaceholder(builder, block, start)
        setText(builder)
        setSelection(start + 1)
        
        // 强制刷新显示
        post {
            invalidate()
            requestLayout()
        }
        
        notifyContentChanged()
    }
    
    /**
     * 在光标位置插入音频
     */
    fun insertAudio(block: NoteBlock) {
        val start = selectionStart
        val builder = SpannableStringBuilder(text)
        
        insertAudioPlaceholder(builder, block, start)
        setText(builder)
        setSelection(start + 1)
        
        notifyContentChanged()
    }
    
    private fun insertImagePlaceholder(builder: SpannableStringBuilder, block: NoteBlock, position: Int = builder.length) {
        val span = ImageMediaSpan(context, block)
        blockMap[block.id] = block
        
        builder.insert(position, OBJ_REPLACEMENT_CHAR.toString())
        builder.setSpan(span, position, position + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    
    private fun insertAudioPlaceholder(builder: SpannableStringBuilder, block: NoteBlock, position: Int = builder.length) {
        val span = AudioMediaSpan(context, block)
        blockMap[block.id] = block
        
        builder.insert(position, OBJ_REPLACEMENT_CHAR.toString())
        builder.setSpan(span, position, position + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    
    fun updateAudioPlaybackState(blockId: String, isPlaying: Boolean, progress: Float) {
        val text = text ?: return
        val spans = text.getSpans(0, text.length, AudioMediaSpan::class.java)
        
        // 通过Context获取Activity并更新调试信息
        val activity = context as? com.example.xnote.NoteEditActivity
        activity?.let { act ->
            val method = act.javaClass.getDeclaredMethod("updateDebugStatus", String::class.java)
            method.isAccessible = true
            method.invoke(act, "RichEditText: 查找到${spans.size}个音频Span")
        }
        
        var updated = false
        var targetSpan: AudioMediaSpan? = null
        for (audioSpan in spans) {
            if (audioSpan.block.id == blockId) {
                audioSpan.setPlayingState(isPlaying, progress)
                targetSpan = audioSpan
                updated = true
                activity?.let { act ->
                    val method = act.javaClass.getDeclaredMethod("updateDebugStatus", String::class.java)
                    method.isAccessible = true
                    method.invoke(act, "找到目标Span: ${blockId.take(8)}, 设置状态: $isPlaying")
                }
            } else if (audioSpan.isPlaying) {
                // 只有当前正在播放的其他音频才需要停止
                audioSpan.setPlayingState(false, 0f)
                updated = true
            }
        }
        
        if (updated && targetSpan != null) {
            // 强制刷新显示 - 使用最有效的重绘方式
            post {
                // 通知系统Span已更改（这是关键修复）
                val currentText = text
                if (currentText is android.text.Spannable && targetSpan != null) {
                    val spanStart = currentText.getSpanStart(targetSpan)
                    val spanEnd = currentText.getSpanEnd(targetSpan)
                    if (spanStart >= 0 && spanEnd >= 0) {
                        // 移除并重新添加span来触发重绘
                        currentText.removeSpan(targetSpan)
                        currentText.setSpan(targetSpan, spanStart, spanEnd, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
                
                // 标准重绘调用
                invalidate()
                requestLayout()
            }
        } else {
            activity?.let { act ->
                val method = act.javaClass.getDeclaredMethod("updateDebugStatus", String::class.java)
                method.isAccessible = true
                method.invoke(act, "未找到目标Span: ${blockId.take(8)}")
            }
        }
        
        android.util.Log.d("RichEditText", "Updated audio state: blockId=$blockId, playing=$isPlaying, progress=$progress")
    }
    
    /**
     * 获取音频块的播放状态
     */
    fun getAudioPlaybackState(blockId: String): Pair<Boolean, Float>? {
        val text = text ?: return null
        val spans = text.getSpans(0, text.length, AudioMediaSpan::class.java)
        
        return spans.find { it.block.id == blockId }?.let { audioSpan ->
            Pair(audioSpan.isPlaying, audioSpan.playProgress)
        }
    }
    
    private fun notifyContentChanged() {
        onContentChangedListener?.invoke(toBlocks())
    }
    
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private val touchSlop = 20f // 触摸阈值，超过这个距离认为是滑动
    private val clickTimeout = 500L // 点击超时，超过这个时间认为是长按

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                downTime = System.currentTimeMillis()
                
                // 检查是否点击了媒体内容，如果是则不调用super
                val offset = getOffsetForPosition(event.x, event.y)
                val spans = text?.getSpans(offset, offset, MediaSpan::class.java)
                
                if (spans?.isNotEmpty() == true) {
                    val span = spans.first()
                    if (isClickOnMediaContent(event.x, event.y, offset, span)) {
                        // 点击在媒体内容上，不调用super避免开始文本选择
                        return true
                    }
                }
                
                return super.onTouchEvent(event)
            }
            MotionEvent.ACTION_UP -> {
                val upTime = System.currentTimeMillis()
                val deltaTime = upTime - downTime
                val deltaX = Math.abs(event.x - downX)
                val deltaY = Math.abs(event.y - downY)
                val isClick = deltaX < touchSlop && deltaY < touchSlop && deltaTime < clickTimeout
                
                android.util.Log.d("RichEditText", "ACTION_UP: isClick=$isClick, deltaX=$deltaX, deltaY=$deltaY, deltaTime=$deltaTime")
                
                if (isClick) {
                    // 这是一个真正的点击，检查是否点击了媒体
                    val offset = getOffsetForPosition(event.x, event.y)
                    val spans = text?.getSpans(offset, offset, MediaSpan::class.java)
                    
                    android.util.Log.d("RichEditText", "Click detected at offset=$offset, spans count=${spans?.size ?: 0}")
                    
                    spans?.firstOrNull()?.let { span ->
                        android.util.Log.d("RichEditText", "Found span: ${span.javaClass.simpleName}, blockId=${span.block.id}")
                        // 进一步检查是否真的点击在媒体内容区域内
                        if (isClickOnMediaContent(event.x, event.y, offset, span)) {
                            android.util.Log.d("RichEditText", "Click on media content confirmed, invoking listener")
                            onMediaClickListener?.invoke(span.block)
                            return true // 直接返回true，不调用super，避免文本选择
                        } else {
                            android.util.Log.d("RichEditText", "Click not on media content area")
                        }
                    }
                }
                return super.onTouchEvent(event)
            }
            else -> return super.onTouchEvent(event)
        }
    }
    
    /**
     * 检查点击是否在媒体内容区域内（而不是空白区域）
     */
    private fun isClickOnMediaContent(x: Float, y: Float, offset: Int, span: MediaSpan): Boolean {
        try {
            val layout = layout ?: return false
            val line = layout.getLineForOffset(offset)
            val lineStart = layout.getLineStart(line)
            val lineBaseline = layout.getLineBaseline(line)
            val lineLeft = layout.getLineLeft(line)
            
            // 获取span在这一行的位置
            val spanStart = text?.getSpanStart(span) ?: return false
            val spanEnd = text?.getSpanEnd(span) ?: return false
            
            if (offset >= spanStart && offset < spanEnd) {
                // 计算媒体内容的实际显示区域
                val (width, height) = span.getDisplaySize()
                val spanStartX = layout.getPrimaryHorizontal(spanStart)
                val spanLeft = lineLeft + spanStartX
                val spanTop = lineBaseline - height
                val spanRight = spanLeft + width
                val spanBottom = lineBaseline
                
                // 检查点击是否在实际内容区域内
                return x >= spanLeft && x <= spanRight && y >= spanTop && y <= spanBottom
            }
        } catch (e: Exception) {
            SecurityLog.e("RichEditText", "Error checking media content click", e)
        }
        return false
    }
}

/**
 * 媒体占位符基类
 */
abstract class MediaSpan(val block: NoteBlock) : ReplacementSpan() {
    
    abstract fun getDisplaySize(): Pair<Int, Int>
    
    override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        val (width, height) = getDisplaySize()
        fm?.let {
            it.ascent = -height
            it.descent = 0
            it.top = it.ascent
            it.bottom = it.descent
        }
        return width
    }
}

/**
 * 图片占位符
 */
class ImageMediaSpan(
    private val context: Context,
    block: NoteBlock
) : MediaSpan(block) {
    
    private var imageBitmap: Bitmap? = null
    private var displayWidth = 200
    private var displayHeight = 150
    
    init {
        loadImage()
    }
    
    private fun loadImage() {
        block.url?.let { imagePath ->
            try {
                val options = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                BitmapFactory.decodeFile(imagePath, options)
                
                // 计算缩放比例
                val maxWidth = 300
                val maxHeight = 200
                val scaleFactor = calculateScaleFactor(options.outWidth, options.outHeight, maxWidth, maxHeight)
                
                // 加载实际图片
                val actualOptions = BitmapFactory.Options().apply {
                    inSampleSize = scaleFactor
                }
                imageBitmap = BitmapFactory.decodeFile(imagePath, actualOptions)
                
                // 更新显示尺寸
                imageBitmap?.let { bitmap ->
                    displayWidth = bitmap.width
                    displayHeight = bitmap.height
                }
            } catch (e: Exception) {
                SecurityLog.e("RichEditText", "Failed to load image for display", e)
                // 加载失败时使用默认尺寸
                displayWidth = 200
                displayHeight = 150
            }
        }
    }
    
    private fun calculateScaleFactor(width: Int, height: Int, maxWidth: Int, maxHeight: Int): Int {
        var scaleFactor = 1
        
        if (width > maxWidth || height > maxHeight) {
            val halfWidth = width / 2
            val halfHeight = height / 2
            
            while (halfWidth / scaleFactor >= maxWidth && halfHeight / scaleFactor >= maxHeight) {
                scaleFactor *= 2
            }
        }
        
        return scaleFactor
    }
    
    override fun getDisplaySize(): Pair<Int, Int> {
        return Pair(displayWidth, displayHeight)
    }
    
    override fun draw(
        canvas: Canvas,
        text: CharSequence?,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: Paint
    ) {
        canvas.save()
        canvas.translate(x, top.toFloat())
        
        val bitmap = imageBitmap
        if (bitmap != null && !bitmap.isRecycled) {
            // 绘制实际图片
            canvas.drawBitmap(bitmap, 0f, 0f, paint)
        } else {
            // 如果图片加载失败，绘制占位符
            val placeholder = context.getDrawable(android.R.drawable.ic_menu_gallery)
            placeholder?.setBounds(0, 0, displayWidth, displayHeight)
            placeholder?.draw(canvas)
        }
        
        // 绘制边框
        val borderPaint = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            color = Color.GRAY
            isAntiAlias = true
        }
        canvas.drawRect(0f, 0f, displayWidth.toFloat(), displayHeight.toFloat(), borderPaint)
        
        canvas.restore()
    }
}

/**
 * 音频占位符
 */
class AudioMediaSpan(
    private val context: Context,
    block: NoteBlock
) : MediaSpan(block) {
    
    private val paint = Paint().apply {
        isAntiAlias = true
        textSize = 14f * context.resources.displayMetrics.density
    }
    
    // 播放状态管理
    var isPlaying = false
        private set
    var playProgress = 0f // 播放进度 0.0-1.0
        private set
    
    /**
     * 设置播放状态
     */
    fun setPlayingState(playing: Boolean, progress: Float = 0f) {
        isPlaying = playing
        playProgress = progress.coerceIn(0f, 1f)
        
        // 可选：保留少量调试信息
        val activity = context as? com.example.xnote.NoteEditActivity
        activity?.let { act ->
            val method = act.javaClass.getDeclaredMethod("updateDebugStatus", String::class.java)
            method.isAccessible = true
            method.invoke(act, "♪ ${block.id.take(8)}: ${if(playing) "▶" else "⏸"} ${(progress*100).toInt()}%")
        }
    }
    
    override fun getDisplaySize(): Pair<Int, Int> {
        return Pair(800, 160)
    }
    
    override fun draw(
        canvas: Canvas,
        text: CharSequence?,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: Paint
    ) {
        canvas.save()
        canvas.translate(x, top.toFloat())

        // 绘制音频背景
        this.paint.style = Paint.Style.FILL
        this.paint.color = Color.parseColor("#FFF3E0")
        canvas.drawRoundRect(0f, 0f, 800f, 160f, 20f, 20f, this.paint)

        // 绘制播放/暂停按钮
        this.paint.color = Color.parseColor("#FF9800")
        val centerY = 80f

        if (isPlaying) {
            // 暂停按钮（两个矩形）
            canvas.drawRect(30f, centerY - 30f, 45f, centerY + 30f, this.paint)
            canvas.drawRect(55f, centerY - 30f, 70f, centerY + 30f, this.paint)
        } else {
            // 播放按钮（三角形）
            val playButton = Path().apply {
                moveTo(35f, centerY - 30f)
                lineTo(90f, centerY)
                lineTo(35f, centerY + 30f)
                close()
            }
            canvas.drawPath(playButton, this.paint)
        }

        // 绘制时长
        this.paint.color = Color.BLACK
        this.paint.style = Paint.Style.FILL
        this.paint.textSize = 20f * context.resources.displayMetrics.density
        val duration = block.duration ?: 0
        val durationText = String.format("%02d:%02d", duration / 60, duration % 60)
    // 计算时长文字的y轴中心
    val timeTextY = centerY + 10f
    canvas.drawText(durationText, 120f, timeTextY, this.paint)

    // 计算时长文字宽度
    val textWidth = this.paint.measureText(durationText)
    val waveStartX = 120f + textWidth + 20f // 20f为间距，可调整

        // 绘制进度条背景
        this.paint.color = Color.parseColor("#E0E0E0")
        val progressBarY = centerY + 30f
        val progressBarWidth = 600f
        val progressBarHeight = 8f
        canvas.drawRoundRect(120f, progressBarY - progressBarHeight/2, 120f + progressBarWidth, progressBarY + progressBarHeight/2, progressBarHeight/2, progressBarHeight/2, this.paint)

        // 绘制进度条
        this.paint.color = Color.parseColor("#FF9800")
        val progressWidth = progressBarWidth * playProgress
        if (progressWidth > 0) {
            canvas.drawRoundRect(120f, progressBarY - progressBarHeight/2, 120f + progressWidth, progressBarY + progressBarHeight/2, progressBarHeight/2, progressBarHeight/2, this.paint)
        }

        // 绘制进度指示器
        this.paint.color = Color.parseColor("#bd0707ff")
        canvas.drawCircle(120f + progressWidth, progressBarY, 12f, this.paint)

        // 绘制波形线（简化）
        this.paint.strokeWidth = 3f
        this.paint.style = Paint.Style.STROKE
        this.paint.color = Color.parseColor("#ec0909ff")
        // 让波形线的纵向中心与时长文字的纵向中心一致
        for (i in 0..15) {
            val x1 = waveStartX + i * 30f//./120-160
            val height = (Math.random() * 30 + 8).toFloat()
            // 以 timeTextY 为中心
            canvas.drawLine(x1, timeTextY - 15f - height/2, x1, timeTextY - 15f+ height/2, this.paint)
        }

        canvas.restore()
    }
}