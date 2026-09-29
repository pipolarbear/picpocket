package com.picpocket.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.picpocket.app.ui.screens.detail.DocumentDetailScreen
import com.picpocket.app.ui.screens.home.HomeScreen
import com.picpocket.app.ui.screens.scanner.ScannerScreen
import com.picpocket.app.ui.screens.donate.DonateScreen
import com.picpocket.app.ui.screens.deleted.DeletedDocumentsScreen
import com.picpocket.app.ui.screens.pairing.DevicePairingScreen
import com.picpocket.app.ui.screens.settings.SettingsScreen
import com.picpocket.app.ui.screens.settings.TracingScreen
import com.picpocket.app.ui.screens.sync.SyncScreen
import com.picpocket.app.ui.screens.tags.TagManagementScreen
import com.picpocket.app.ui.screens.viewer.PageViewerScreen
import com.picpocket.app.ui.screens.workflows.WorkflowsScreen

object Routes {
    const val HOME = "home"
    const val SCANNER = "scanner"
    const val APPEND_SCANNER = "scanner/{documentId}"
    const val DOCUMENT_DETAIL = "document/{documentId}"
    const val PAGE_VIEWER = "viewer/{documentId}/{pageIndex}"
    const val SETTINGS = "settings"
    const val SYNC = "sync"
    const val DONATE = "donate"
    const val TAGS = "tags"
    const val WORKFLOWS = "workflows?workflowId={workflowId}"
    const val TRACING = "tracing"
    const val DELETED = "deleted"
    const val PAIRING = "pairing"

    fun documentDetail(documentId: String) = "document/$documentId"
    fun pageViewer(documentId: String, pageIndex: Int) = "viewer/$documentId/$pageIndex"
    fun appendScanner(documentId: String) = "scanner/$documentId"
    fun workflows(workflowId: Long? = null) =
        if (workflowId == null) "workflows" else "workflows?workflowId=$workflowId"
}

@Composable
fun PicPocketNavGraph(navController: NavHostController) {
    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                onScanClick = { navController.navigate(Routes.SCANNER) },
                onDocumentClick = { docId -> navController.navigate(Routes.documentDetail(docId)) },
                onSettingsClick = { navController.navigate(Routes.SETTINGS) },
            )
        }
        composable(Routes.SCANNER) {
            ScannerScreen(
                onNavigateBack = { navController.popBackStack() },
                onDocumentSaved = { docId ->
                    navController.popBackStack()
                    navController.navigate(Routes.documentDetail(docId))
                },
            )
        }
        composable(
            route = Routes.DOCUMENT_DETAIL,
            arguments = listOf(navArgument("documentId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val documentId = backStackEntry.arguments?.getString("documentId") ?: return@composable
            DocumentDetailScreen(
                documentId = documentId,
                onNavigateBack = { navController.popBackStack() },
                onPageView = { docId, pageIndex ->
                    navController.navigate(Routes.pageViewer(docId, pageIndex))
                },
                onAddPage = { docId ->
                    navController.navigate(Routes.appendScanner(docId))
                },
            )
        }
        composable(
            route = Routes.PAGE_VIEWER,
            arguments = listOf(
                navArgument("documentId") { type = NavType.StringType },
                navArgument("pageIndex") { type = NavType.IntType },
            ),
        ) { backStackEntry ->
            val documentId = backStackEntry.arguments?.getString("documentId") ?: return@composable
            val pageIndex = backStackEntry.arguments?.getInt("pageIndex") ?: 0
            PageViewerScreen(
                documentId = documentId,
                initialPageIndex = pageIndex,
                onNavigateBack = { navController.popBackStack() },
            )
        }
        composable(
            route = Routes.APPEND_SCANNER,
            arguments = listOf(navArgument("documentId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val documentId = backStackEntry.arguments?.getString("documentId") ?: return@composable
            ScannerScreen(
                documentId = documentId,
                onNavigateBack = { navController.popBackStack() },
                onDocumentSaved = { docId ->
                    navController.popBackStack()
                    navController.navigate(Routes.pageViewer(docId, 0))
                },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onNavigateBack = { navController.popBackStack() },
                onDonateClick = { navController.navigate(Routes.DONATE) },
                onTagsClick = { navController.navigate(Routes.TAGS) },
                onWorkflowsClick = { navController.navigate(Routes.workflows()) },
                onSyncClick = { navController.navigate(Routes.SYNC) },
                onTracingClick = { navController.navigate(Routes.TRACING) },
            )
        }
        composable(Routes.TRACING) {
            TracingScreen(
                onNavigateBack = { navController.popBackStack() },
            )
        }
        composable(Routes.SYNC) {
            SyncScreen(
                onNavigateBack = { navController.popBackStack() },
            )
        }
        composable(Routes.DELETED) {
            DeletedDocumentsScreen(
                onNavigateBack = { navController.popBackStack() },
            )
        }
        composable(Routes.PAIRING) {
            DevicePairingScreen(
                onNavigateBack = { navController.popBackStack() },
            )
        }
        composable(Routes.DONATE) {
            DonateScreen(
                onNavigateBack = { navController.popBackStack() },
            )
        }
        composable(Routes.TAGS) {
            TagManagementScreen(
                onNavigateBack = { navController.popBackStack() },
                onOpenWorkflow = { workflowId -> navController.navigate(Routes.workflows(workflowId)) },
            )
        }
        composable(
            route = Routes.WORKFLOWS,
            arguments = listOf(
                navArgument("workflowId") {
                    type = NavType.LongType
                    defaultValue = -1L
                },
            ),
        ) { backStackEntry ->
            val workflowId = backStackEntry.arguments?.getLong("workflowId")?.takeIf { it > 0 }
            WorkflowsScreen(
                onNavigateBack = { navController.popBackStack() },
                initialWorkflowId = workflowId,
            )
        }
    }
}
