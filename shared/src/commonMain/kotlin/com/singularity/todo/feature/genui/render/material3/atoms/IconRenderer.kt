package com.singularity.todo.feature.genui.render.material3.atoms

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Note
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Anchor
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderSpecial
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.vector.ImageVector
import com.singularity.todo.feature.genui.catalog.UiNode
import com.singularity.todo.feature.genui.render.ComponentRegistry
import com.singularity.todo.feature.genui.render.genuiTag

private val ICONS: Map<String, ImageVector> = mapOf(
    "star" to Icons.Filled.Star,
    "check" to Icons.Filled.Check,
    "close" to Icons.Filled.Close,
    "add" to Icons.Filled.Add,
    "delete" to Icons.Filled.Delete,
    "edit" to Icons.Filled.Edit,
    "settings" to Icons.Filled.Settings,
    "search" to Icons.Filled.Search,
    "info" to Icons.Filled.Info,
    "warning" to Icons.Filled.Warning,
    "home" to Icons.Filled.Home,
    "work" to Icons.Filled.Work,
    "folder" to Icons.Filled.Folder,
    "folderspecial" to Icons.Filled.FolderSpecial,
    "note" to Icons.AutoMirrored.Filled.Note,
    "calendar" to Icons.Filled.CalendarMonth,
    "date" to Icons.Filled.CalendarMonth,
    "daterange" to Icons.Filled.DateRange,
    "cart" to Icons.Filled.ShoppingCart,
    "shoppingcart" to Icons.Filled.ShoppingCart,
    "flight" to Icons.Filled.Flight,
    "hospital" to Icons.Filled.LocalHospital,
    "school" to Icons.Filled.School,
    "fitness" to Icons.Filled.FitnessCenter,
    "pets" to Icons.Filled.Pets,
    "music" to Icons.Filled.MusicNote,
    "code" to Icons.Filled.Code,
    "build" to Icons.Filled.Build,
    "anchor" to Icons.Filled.Anchor,
    "favorite" to Icons.Filled.Favorite,
    "heart" to Icons.Filled.Favorite,
)

/** Resolves a string icon name to a Material icon. Public for testing. */
internal fun resolveIcon(name: String): ImageVector? = ICONS[name.lowercase()]

internal fun ComponentRegistry.registerIcon(): Unit = register("icon") { node, _, modifier ->
    val i = node as UiNode.Icon
    val icon = resolveIcon(i.name) ?: Icons.Filled.Info
    Icon(
        imageVector = icon,
        contentDescription = null,
        modifier = modifier.genuiTag("icon_${i.name}", "Icon: ${i.name}"),
    )
}
