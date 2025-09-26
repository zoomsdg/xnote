package com.example.xnote

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.xnote.databinding.ActivityMainBinding

/**
 * 简化版的MainActivity用于测试应用启动
 */
class MainActivitySimple : AppCompatActivity() {
    
    private lateinit var binding: ActivityMainBinding
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        try {
            binding = ActivityMainBinding.inflate(layoutInflater)
            setContentView(binding.root)
            
            // 最基本的初始化
            binding.fabNewNote.setOnClickListener {
                // 简单的点击响应
            }
            
        } catch (e: Exception) {
            e.printStackTrace()
            // 如果绑定失败，使用简单的布局
            setContentView(android.R.layout.simple_list_item_1)
        }
    }
}