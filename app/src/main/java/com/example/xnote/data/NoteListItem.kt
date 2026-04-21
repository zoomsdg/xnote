package com.example.xnote.data

/**
 * RecyclerView 的两种行：月份头 + 记事条目。
 * 月份头用于按 yyyy-MM 折叠/展开；折叠状态由 Adapter 维护。
 */
sealed class NoteListItem {
    abstract val itemKey: String

    data class MonthHeader(
        /** 键：格式 "yyyy-MM"，如 "2026-01" */
        val yearMonth: String,
        /** 展示标签：如 "2026年01月" */
        val displayLabel: String,
        /** 该月份下记事数量 */
        val count: Int,
        /** 是否处于折叠状态 */
        val collapsed: Boolean
    ) : NoteListItem() {
        override val itemKey: String = "header:$yearMonth"
    }

    data class NoteEntry(
        val summary: NoteSummary
    ) : NoteListItem() {
        override val itemKey: String = "note:${summary.id}"
    }
}
