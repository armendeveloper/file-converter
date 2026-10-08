package dev.armen.fileconverter.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.armen.fileconverter.di.AppContainer
import dev.armen.fileconverter.ui.convert.ConvertScreen
import dev.armen.fileconverter.ui.convert.ConvertViewModel
import dev.armen.fileconverter.ui.home.HomeScreen
import dev.armen.fileconverter.ui.home.HomeViewModel
import dev.armen.fileconverter.ui.settings.SettingsScreen
import dev.armen.fileconverter.ui.settings.SettingsViewModel

/** [shareSignal] increases each time files are shared into the app; it opens the Convert screen. */
@Composable
fun AppNavHost(container: AppContainer, shareSignal: Int) {
    val navController = rememberNavController()
    LaunchedEffect(shareSignal) {
        if (shareSignal > 0) navController.navigate(Routes.CONVERT) { launchSingleTop = true }
    }
    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            val viewModel = viewModel { HomeViewModel(container.history) }
            HomeScreen(
                viewModel = viewModel,
                onFilesPicked = { uris ->
                    container.selection.set(uris.mapNotNull { runCatching { container.inputResolver.resolve(it) }.getOrNull() })
                    navController.navigate(Routes.CONVERT)
                },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }
        composable(Routes.SETTINGS) {
            val viewModel = viewModel { SettingsViewModel(container.settings, container::cloudClient, container.history) }
            SettingsScreen(viewModel, onBack = { navController.popBackStack() })
        }
        // Replaced in Task 15 (Convert) and Task 16 (Progress, Result).
        composable(Routes.CONVERT) {
            val viewModel = viewModel {
                ConvertViewModel(container.selection, container.settings, container.inputResolver, container.scheduler, container.queueDir())
            }
            ConvertScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onBatchStarted = { tag -> navController.navigate(Routes.progress(tag)) },
            )
        }
        composable(Routes.PROGRESS) { PlaceholderScreen("Progress") }
        composable(Routes.RESULT) { PlaceholderScreen("Result") }
    }
}

@Composable
private fun PlaceholderScreen(name: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("$name screen") }
}
