package com.example.evidkeeper

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.evidkeeper.data.settings.SettingsStore
import com.example.evidkeeper.data.storage.StorageManager
import com.example.evidkeeper.ui.create.CreateScreen
import com.example.evidkeeper.ui.create.CreateViewModel
import com.example.evidkeeper.ui.detail.DetailScreen
import com.example.evidkeeper.ui.detail.DetailViewModel
import com.example.evidkeeper.ui.main.MainScreen
import com.example.evidkeeper.ui.main.MainViewModel
import com.example.evidkeeper.ui.settings.SettingsScreen
import com.example.evidkeeper.ui.settings.SettingsViewModel
import com.example.evidkeeper.ui.theme.EvidKeeperTheme

class MainActivity : ComponentActivity() {

    private val settingsStore by lazy { SettingsStore(this) }
    private val storageManager by lazy { StorageManager(this, settingsStore) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val sm = storageManager
        val ss = settingsStore

        setContent {
            EvidKeeperTheme {
                val navController = rememberNavController()
                NavHost(
                    navController = navController,
                    startDestination = "main",
                ) {
                    // ── 主页面 ──
                    composable("main") {
                        val vm: MainViewModel = viewModel(
                            factory = object : ViewModelProvider.Factory {
                                @Suppress("UNCHECKED_CAST")
                                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                                    return MainViewModel(sm) as T
                                }
                            }
                        )
                        MainScreen(
                            viewModel = vm,
                            onOpenDetail = { id -> navController.navigate("detail/$id") },
                            onOpenCreate = { navController.navigate("create") },
                            onOpenSettings = { navController.navigate("settings") },
                        )
                    }

                    // ── 事项详情页 ──
                    composable(
                        "detail/{itemId}",
                        arguments = listOf(navArgument("itemId") { type = NavType.IntType }),
                    ) { backStackEntry ->
                        val itemId = backStackEntry.arguments?.getInt("itemId") ?: 0
                        val vm: DetailViewModel = viewModel(
                            factory = object : ViewModelProvider.Factory {
                                @Suppress("UNCHECKED_CAST")
                                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                                    return DetailViewModel(sm, itemId) as T
                                }
                            }
                        )
                        DetailScreen(
                            viewModel = vm,
                            onBack = { navController.popBackStack() },
                        )
                    }

                    // ── 新建事项页 ──
                    composable("create") {
                        val vm: CreateViewModel = viewModel(
                            factory = object : ViewModelProvider.Factory {
                                @Suppress("UNCHECKED_CAST")
                                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                                    return CreateViewModel(sm) as T
                                }
                            }
                        )
                        CreateScreen(
                            viewModel = vm,
                            onBack = { navController.popBackStack() },
                        )
                    }

                    // ── 设置页 ──
                    composable("settings") {
                        val vm: SettingsViewModel = viewModel(
                            factory = object : ViewModelProvider.Factory {
                                @Suppress("UNCHECKED_CAST")
                                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                                    return SettingsViewModel(ss) as T
                                }
                            }
                        )
                        SettingsScreen(
                            viewModel = vm,
                            onBack = { navController.popBackStack() },
                        )
                    }
                }
            }
        }
    }
}
