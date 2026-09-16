package dev.creds.vault

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import dev.creds.vault.items.VaultListRoute
import dev.creds.vault.tags.ManageTagsRoute
import kotlinx.serialization.Serializable

@Serializable
data object VaultListDestination

@Serializable
data object ManageTagsDestination

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
                onLock = onLock,
            )
        }
        composable<ManageTagsDestination> {
            ManageTagsRoute(onBack = { navController.popBackStack() })
        }
    }
}
