package com.singularity.todo.feature.genui.render.material3.atoms

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Note
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FolderSpecial
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Star
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Which pictogram a name asks for, and what a name that asks for nothing gets.
 *
 * The catalogue offers a model the names it may write, and this lookup turns one back into a glyph.
 * A name it cannot resolve is silently replaced by the information glyph for the reader, so every
 * name the catalogue offers has to resolve here, and a name outside the table has to come back as
 * `null` — the renderer's fallback is written on top of that answer.
 */
@Tag("fast")
class ResolveIconTest {
    @Test
    fun everyNameTheCatalogueOffersResolves() {
        // The `icon` schema's own list of names (feature/genui/catalog/SingularityComponents.kt).
        val offered =
            listOf(
                "star",
                "check",
                "close",
                "add",
                "delete",
                "edit",
                "settings",
                "search",
                "info",
                "warning",
                "home",
                "work",
                "folder",
                "note",
                "calendar",
                "date",
                "cart",
                "flight",
                "hospital",
                "school",
                "fitness",
                "pets",
                "music",
                "code",
                "build",
                "anchor",
                "favorite",
            )

        offered.forEach { name -> assertNotNull(resolveIcon(name), name) }
    }

    @Test
    fun namesTheCatalogueDoesNotOfferResolveToo() {
        listOf("folderspecial", "daterange", "shoppingcart", "heart").forEach { name ->
            assertNotNull(resolveIcon(name), name)
        }
    }

    @Test
    fun theLookupIgnoresCase() {
        assertSame(Icons.Filled.Star, resolveIcon("STAR"))
        assertSame(Icons.Filled.FolderSpecial, resolveIcon("folderSpecial"))
    }

    @Test
    fun bothNamesOfAnIconAnswerTheSameGlyph() {
        assertSame(Icons.Filled.ShoppingCart, resolveIcon("cart"))
        assertSame(Icons.Filled.ShoppingCart, resolveIcon("shoppingcart"))
        assertSame(Icons.Filled.Favorite, resolveIcon("heart"))
        assertSame(Icons.Filled.Favorite, resolveIcon("favorite"))
        assertSame(Icons.Filled.CalendarMonth, resolveIcon("calendar"))
        assertSame(Icons.Filled.CalendarMonth, resolveIcon("date"))
    }

    @Test
    fun noteIsTheMirroredGlyph() {
        assertSame(Icons.AutoMirrored.Filled.Note, resolveIcon("note"))
    }

    @Test
    fun aNameTheRendererDoesNotKnowResolvesToNothing() {
        assertNull(resolveIcon("rocket"))
        assertNull(resolveIcon(""))
    }
}
