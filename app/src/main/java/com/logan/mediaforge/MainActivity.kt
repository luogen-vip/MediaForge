package com.logan.mediaforge

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.logan.mediaforge.core.TaskRepository
import com.logan.mediaforge.ui.AppNav
import com.logan.mediaforge.ui.MediaForgeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TaskRepository.init(applicationContext)
        setContent {
            MediaForgeTheme {
                AppNav()
            }
        }
    }
}
