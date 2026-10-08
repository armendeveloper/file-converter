package dev.armen.fileconverter.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.armen.fileconverter.di.AppContainer
import dev.armen.fileconverter.ui.batch.BatchViewModel
import dev.armen.fileconverter.ui.batch.ProgressScreen
import dev.armen.fileconverter.ui.batch.ResultScreen
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
        val batchArguments = listOf(navArgument(Routes.BATCH_ARG) { type = NavType.StringType })
        composable(Routes.PROGRESS, arguments = batchArguments) { entry ->
            val tag = entry.arguments?.getString(Routes.BATCH_ARG).orEmpty()
            val viewModel = viewModel(key = "progress-$tag") { BatchViewModel(tag, container.scheduler, container.queueDir()) }
            ProgressScreen(
                viewModel = viewModel,
                onFinished = {
                    navController.navigate(Routes.result(tag)) { popUpTo(Routes.PROGRESS) { inclusive = true } }
                },
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.RESULT, arguments = batchArguments) { entry ->
            val tag = entry.arguments?.getString(Routes.BATCH_ARG).orEmpty()
            val viewModel = viewModel(key = "result-$tag") { BatchViewModel(tag, container.scheduler, container.queueDir()) }
            ResultScreen(
                viewModel = viewModel,
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onRetry = { navController.popBackStack(Routes.CONVERT, inclusive = false) },
                onDone = { navController.popBackStack(Routes.HOME, inclusive = false) },
            )
        }
    }
}
