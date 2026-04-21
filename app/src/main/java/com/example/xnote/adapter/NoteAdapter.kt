package com.example.xnote.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.xnote.data.NoteListItem
import com.example.xnote.data.NoteSummary
import com.example.xnote.databinding.ItemMonthHeaderBinding
import com.example.xnote.databinding.ItemNoteBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class NoteAdapter(
    private val onNoteClick: (NoteSummary) -> Unit,
    private val onNoteLongClick: (NoteSummary) -> Unit = {},
    private val onPinClick: (NoteSummary) -> Unit = {},
    private val onSelectionChanged: (Set<String>) -> Unit = {}
) : ListAdapter<NoteListItem, RecyclerView.ViewHolder>(ListItemDiffCallback()) {

    companion object {
        private const val VIEW_TYPE_HEADER = 0
        private const val VIEW_TYPE_NOTE = 1
    }

    private var isSelectionMode = false
    private val selectedNotes = mutableSetOf<String>()

    // 原始记事列表（上游直接给的 List<NoteSummary>），折叠/展开都在它基础上重建
    private var rawNotes: List<NoteSummary> = emptyList()
    // 当前处于折叠状态的月份键集合，格式 "yyyy-MM"
    private val collapsedMonths = mutableSetOf<String>()

    /**
     * 替换数据源。按月份分桶，保留已有折叠状态。
     */
    fun setNotes(notes: List<NoteSummary>) {
        rawNotes = notes
        rebuildAndSubmit()
    }

    private fun rebuildAndSubmit() {
        val monthKeyFormat = SimpleDateFormat("yyyy-MM", Locale.getDefault())

        // 按月份分桶；LinkedHashMap 保留上游已有的排序（置顶优先、时间降序）
        val buckets = LinkedHashMap<String, MutableList<NoteSummary>>()
        for (note in rawNotes) {
            val key = monthKeyFormat.format(Date(note.lastModified))
            buckets.getOrPut(key) { mutableListOf() }.add(note)
        }

        // 月份自身按 yyyy-MM 降序（最新月在前）
        val sortedKeys = buckets.keys.sortedDescending()

        val out = ArrayList<NoteListItem>(rawNotes.size + sortedKeys.size)
        for (key in sortedKeys) {
            val bucket = buckets.getValue(key)
            val parts = key.split("-")
            val label = "${parts[0]}年${parts[1]}月"
            val collapsed = collapsedMonths.contains(key)

            out.add(NoteListItem.MonthHeader(key, label, bucket.size, collapsed))
            if (!collapsed) {
                bucket.forEach { out.add(NoteListItem.NoteEntry(it)) }
            }
        }
        submitList(out)
    }

    private fun toggleMonth(yearMonth: String) {
        if (collapsedMonths.contains(yearMonth)) {
            collapsedMonths.remove(yearMonth)
        } else {
            collapsedMonths.add(yearMonth)
        }
        rebuildAndSubmit()
    }

    // --- 批量选择 -----------------------------------------------------------

    fun setSelectionMode(enabled: Boolean) {
        isSelectionMode = enabled
        if (!enabled) {
            selectedNotes.clear()
        }
        notifyDataSetChanged()
        onSelectionChanged(selectedNotes.toSet())
    }

    fun toggleSelection(noteId: String) {
        if (selectedNotes.contains(noteId)) {
            selectedNotes.remove(noteId)
        } else {
            selectedNotes.add(noteId)
        }
        notifyDataSetChanged()
        onSelectionChanged(selectedNotes.toSet())
    }

    fun selectAll() {
        selectedNotes.clear()
        currentList.forEach {
            if (it is NoteListItem.NoteEntry) selectedNotes.add(it.summary.id)
        }
        notifyDataSetChanged()
        onSelectionChanged(selectedNotes.toSet())
    }

    fun deselectAll() {
        selectedNotes.clear()
        notifyDataSetChanged()
        onSelectionChanged(selectedNotes.toSet())
    }

    fun getSelectedNotes(): Set<String> = selectedNotes.toSet()

    fun hasSelectedNotes(): Boolean = selectedNotes.isNotEmpty()

    // --- ViewHolders --------------------------------------------------------

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is NoteListItem.MonthHeader -> VIEW_TYPE_HEADER
        is NoteListItem.NoteEntry -> VIEW_TYPE_NOTE
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_HEADER -> MonthHeaderViewHolder(
                ItemMonthHeaderBinding.inflate(inflater, parent, false)
            )
            else -> NoteViewHolder(
                ItemNoteBinding.inflate(inflater, parent, false)
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is NoteListItem.MonthHeader -> (holder as MonthHeaderViewHolder).bind(item)
            is NoteListItem.NoteEntry -> (holder as NoteViewHolder).bind(item.summary)
        }
    }

    inner class MonthHeaderViewHolder(
        private val binding: ItemMonthHeaderBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(header: NoteListItem.MonthHeader) {
            binding.tvMonthLabel.text = header.displayLabel
            binding.tvMonthCount.text = "${header.count} 项"
            // 折叠显示 "+"，展开显示 "−"
            binding.tvToggleIcon.text = if (header.collapsed) "+" else "−"
            binding.root.setOnClickListener {
                toggleMonth(header.yearMonth)
            }
        }
    }

    inner class NoteViewHolder(
        private val binding: ItemNoteBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private val dateFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

        fun bind(noteSummary: NoteSummary) {
            binding.apply {
                // 解析预览文本为行
                val contentLines = noteSummary.preview
                    .replace("\\n", "\n")
                    .split("\n")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }

                if (noteSummary.title.isNotEmpty()) {
                    tvLine1.text = noteSummary.title
                    tvLine1.textSize = 16f
                    tvLine1.setTypeface(null, android.graphics.Typeface.BOLD)

                    tvLine2.text = if (contentLines.isNotEmpty()) {
                        contentLines[0]
                    } else {
                        "空记事"
                    }
                } else {
                    tvLine1.text = if (contentLines.isNotEmpty()) {
                        contentLines[0]
                    } else {
                        "空记事"
                    }
                    tvLine1.textSize = 14f
                    tvLine1.setTypeface(null, android.graphics.Typeface.NORMAL)

                    tvLine2.text = if (contentLines.size > 1) {
                        contentLines[1]
                    } else {
                        ""
                    }
                }

                ivPin.visibility = if (noteSummary.isPinned) View.VISIBLE else View.GONE

                tvDate.text = dateFormat.format(Date(noteSummary.lastModified))
                tvBlockCount.text = "${noteSummary.blockCount} 项"

                cbSelect.visibility = if (isSelectionMode) View.VISIBLE else View.GONE
                cbSelect.isChecked = selectedNotes.contains(noteSummary.id)

                root.isSelected = selectedNotes.contains(noteSummary.id)

                root.setOnClickListener {
                    if (isSelectionMode) {
                        toggleSelection(noteSummary.id)
                    } else {
                        onNoteClick(noteSummary)
                    }
                }

                root.setOnLongClickListener {
                    if (!isSelectionMode) {
                        onNoteLongClick(noteSummary)
                        true
                    } else {
                        false
                    }
                }

                cbSelect.setOnClickListener {
                    toggleSelection(noteSummary.id)
                }

                ivPin.setOnClickListener {
                    onPinClick(noteSummary)
                }
            }
        }
    }

    private class ListItemDiffCallback : DiffUtil.ItemCallback<NoteListItem>() {
        override fun areItemsTheSame(oldItem: NoteListItem, newItem: NoteListItem): Boolean {
            return oldItem.itemKey == newItem.itemKey
        }

        override fun areContentsTheSame(oldItem: NoteListItem, newItem: NoteListItem): Boolean {
            return oldItem == newItem
        }
    }
}
