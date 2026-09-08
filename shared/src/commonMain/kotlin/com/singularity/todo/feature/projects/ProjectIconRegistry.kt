package com.singularity.todo.feature.projects

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Anchor
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderSpecial
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Note
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Work
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Registry of Material icons available for projects.
 * DB stores the String key (e.g. `"Work"`), not the [ImageVector].
 * UI resolves via [iconByKey].
 *
 * 18 icons — cognitive load budget agreed in ADR 2026-09-08.
 */
object ProjectIconRegistry {

    val Home = Icons.Filled.Home
    val Work = Icons.Filled.Work
    val Star = Icons.Filled.Star
    val Folder = Icons.Filled.Folder
    val FolderSpecial = Icons.Filled.FolderSpecial
    val Note = Icons.Filled.Note
    val DateRange = Icons.Filled.DateRange
    val ShoppingCart = Icons.Filled.ShoppingCart
    val Flight = Icons.Filled.Flight
    val LocalHospital = Icons.Filled.LocalHospital
    val School = Icons.Filled.School
    val FitnessCenter = Icons.Filled.FitnessCenter
    val Pets = Icons.Filled.Pets
    val MusicNote = Icons.Filled.MusicNote
    val Code = Icons.Filled.Code
    val Build = Icons.Filled.Build
    val Anchor = Icons.Filled.Anchor
    val MoreHoriz = Icons.Filled.MoreHoriz

    /** All available (key, ImageVector) pairs. */
    val all: List<Pair<String, ImageVector>> = listOf(
        "Home" to Home,
        "Work" to Work,
        "Star" to Star,
        "Folder" to Folder,
        "FolderSpecial" to FolderSpecial,
        "Note" to Note,
        "DateRange" to DateRange,
        "ShoppingCart" to ShoppingCart,
        "Flight" to Flight,
        "LocalHospital" to LocalHospital,
        "School" to School,
        "FitnessCenter" to FitnessCenter,
        "Pets" to Pets,
        "MusicNote" to MusicNote,
        "Code" to Code,
        "Build" to Build,
        "Anchor" to Anchor,
        "MoreHoriz" to MoreHoriz,
    )

    /** Maps a DB icon key to its [ImageVector], or `null` if not found. */
    fun iconByKey(key: String?): ImageVector? = key?.let { k ->
        all.find { it.first == k }?.second
    }

    /** Maps an [ImageVector] back to its DB key, or `null` if not registered. */
    fun keyByIcon(icon: ImageVector): String? = all.find { it.second == icon }?.first

    /** Default icon when none is set. */
    val default: ImageVector = Folder
}
