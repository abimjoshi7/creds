package dev.creds.vault

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import dev.creds.vault.generator.GeneratorRoute
import dev.creds.vault.items.ItemDetailRoute
import dev.creds.vault.items.ItemEditorRoute
import dev.creds.vault.items.VaultListRoute
import dev.creds.vault.tags.ManageTagsRoute
import kotlinx.serialization.Serializable

@Serializable
data object VaultListDestination

@Serializable
data object ManageTagsDestination

@Serializable
data object GeneratorDestination

@Serializable
data class ItemDetailDestination(val uuid: String)

/** Edits [uuid], or creates an item from [templateId] when [uuid] is null. */
@Serializable
data class ItemEditorDestination(
    val uuid: String? = null,
    val templateId: String? = null,
)

/**
 * Navigation inside an unlocked vault.
 *
 * Only ever composed while unlocked — locking removes this whole graph from the screen,
 * which is why none of these destinations check lock state themselves. [navController] is
 * owned above the lock switch so that re-unlocking reuses its back stack entries (and
 * their ViewModels) rather than orphaning a new set on every unlock.
 */
@Composable
fun VaultNavHost(
    navController: NavHostController,
    onLock: () -> Unit,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = VaultListDestination,
        modifier = modifier,
    ) {
        composable<VaultListDestination> {
            VaultListRoute(
                onManageTags = { navController.navigate(ManageTagsDestination) { launchSingleTop = true } },
                onOpenGenerator = { navController.navigate(GeneratorDestination) { launchSingleTop = true } },
                onOpenItem = { uuid ->
                    navController.navigate(ItemDetailDestination(uuid)) { launchSingleTop = true }
                },
                onCreateItem = { template ->
                    navController.navigate(ItemEditorDestination(templateId = template.id))
                },
                onLock = onLock,
            )
        }
        composable<ManageTagsDestination> {
            ManageTagsRoute(onBack = { navController.popBackStack() })
        }
        composable<GeneratorDestination> {
            GeneratorRoute(onBack = { navController.popBackStack() })
        }
        composable<ItemDetailDestination> {
            ItemDetailRoute(
                onBack = { navController.popBackStack() },
                onEdit = { uuid ->
                    navController.navigate(ItemEditorDestination(uuid = uuid)) { launchSingleTop = true }
                },
            )
        }
        composable<ItemEditorDestination> { entry ->
            val creating = entry.toRoute<ItemEditorDestination>().uuid == null
            ItemEditorRoute(
                onBack = { navController.popBackStack() },
                onSaved = { uuid ->
                    if (creating) {
                        // A new item lands on its own page, and back from there returns
                        // to the list rather than to an editor for something already saved.
                        navController.navigate(ItemDetailDestination(uuid)) {
                            popUpTo<ItemEditorDestination> { inclusive = true }
                        }
                    } else {
                        navController.popBackStack()
                    }
                },
            )
        }
    }
}
