package dev.creds.vault.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.ui.graphics.vector.ImageVector
import dev.creds.vault.core.model.SmartList
import dev.creds.vault.core.model.Template

/** The glyph for each template, used in list rows and the drawer alike. */
val Template.icon: ImageVector
    get() = when (this) {
        Template.LOGIN -> Icons.Outlined.Key
        Template.CARD -> Icons.Outlined.CreditCard
        Template.BANK_ACCOUNT -> Icons.Outlined.AccountBalance
        Template.NOTE -> Icons.Outlined.Description
        Template.WIFI -> Icons.Outlined.Wifi
        Template.IDENTITY -> Icons.Outlined.Badge
        Template.PASSPORT -> Icons.Outlined.TravelExplore
        Template.SERVER -> Icons.Outlined.Dns
        Template.MISC -> Icons.Outlined.Category
    }

val SmartList.icon: ImageVector
    get() = when (this) {
        SmartList.FAVORITES -> Icons.Outlined.StarOutline
        SmartList.ARCHIVE -> Icons.Outlined.Archive
        SmartList.TRASH -> Icons.Outlined.Delete
        else -> Icons.Outlined.Inventory2
    }

val SmartList.label: String
    get() = when (this) {
        SmartList.ALL -> "All items"
        SmartList.FAVORITES -> "Favorites"
        SmartList.RECENTLY_USED -> "Recently used"
        SmartList.WEAK -> "Weak"
        SmartList.REUSED -> "Reused"
        SmartList.BREACHED -> "Breached"
        SmartList.ARCHIVE -> "Archive"
        SmartList.TRASH -> "Trash"
    }
