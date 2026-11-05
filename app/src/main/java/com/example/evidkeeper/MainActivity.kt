package com.example.evidkeeper

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.remember
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

                // 工厂只建一次。原来写在每个 composable{} 里面，
                // 组合一重组就重新 new 一个，Navigation 内部还要做一次
                // ViewModelStore 查找，属于每次进入页面都白跑的分配。
                val mainFactory = remember(sm) {
                    simpleFactory { MainViewModel(sm) }
                }
                val createFactory = remember(sm, ss) {
                    simpleFactory { CreateViewModel(sm, ss) }
                }
                val settingsFactory = remember(ss) {
                    simpleFactory { SettingsViewModel(ss) }
                }

                NavHost(
                    navController = navController,
                    startDestination = "main",
                ) {
                    // ── 主页面 ──
                    composable("main") {
                        val vm: MainViewModel = viewModel(factory = mainFactory)
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
                        // 详情页工厂依赖 itemId，按 itemId remember，同一事项不重复建
                        val detailFactory = remember(sm, itemId) {
                            simpleFactory { DetailViewModel(sm, itemId) }
                        }
                        val vm: DetailViewModel = viewModel(factory = detailFactory)
                        DetailScreen(
                            viewModel = vm,
                            onBack = { navController.popBackStack() },
                        )
                    }

                    // ── 新建事项页 ──
                    composable("create") {
                        val vm: CreateViewModel = viewModel(factory = createFactory)
                        CreateScreen(
                            viewModel = vm,
                            onBack = { navController.popBackStack() },
                        )
                    }

                    // ── 设置页 ──
                    composable("settings") {
                        val vm: SettingsViewModel = viewModel(factory = settingsFactory)
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

/** 用 lambda 直接构造 ViewModel 的轻量工厂 */
private inline fun <reified T : ViewModel> simpleFactory(
    crossinline create: () -> T,
): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <VM : ViewModel> create(modelClass: Class<VM>): VM = create() as VM
}
