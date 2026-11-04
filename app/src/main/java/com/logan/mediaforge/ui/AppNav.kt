package com.logan.mediaforge.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.logan.mediaforge.ui.screens.AboutScreen
import com.logan.mediaforge.ui.screens.ConvertScreen
import com.logan.mediaforge.ui.screens.DetectScreen
import com.logan.mediaforge.ui.screens.ImageScreen
import com.logan.mediaforge.ui.screens.TasksScreen

private data class TabItem(val label: String, val icon: ImageVector, val route: String)

@Composable
fun AppNav() {
    val nav = rememberNavController()
    val tabs = listOf(
        TabItem("转换", Icons.Filled.SwapHoriz, "convert"),
        TabItem("任务", Icons.Filled.TaskAlt, "tasks"),
        TabItem("图片", Icons.Filled.Image, "image"),
        TabItem("识别", Icons.Filled.Search, "detect"),
        TabItem("关于", Icons.Filled.Info, "about")
    )
    Scaffold(bottomBar = {
        NavigationBar {
            val current by nav.currentBackStackEntryAsState()
            val route = current?.destination?.route
            tabs.forEach { t ->
                NavigationBarItem(
                    selected = route == t.route,
                    onClick = {
                        nav.navigate(t.route) {
                            popUpTo(nav.graph.startDestinationId) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    icon = { Icon(t.icon, contentDescription = t.label) },
                    label = { Text(t.label) }
                )
            }
        }
    }) { pad ->
        NavHost(nav, startDestination = "convert", modifier = Modifier.padding(pad)) {
            composable("convert") { ConvertScreen() }
            composable("tasks") { TasksScreen() }
            composable("image") { ImageScreen() }
            composable("detect") { DetectScreen() }
            composable("about") { AboutScreen() }
        }
    }
}
