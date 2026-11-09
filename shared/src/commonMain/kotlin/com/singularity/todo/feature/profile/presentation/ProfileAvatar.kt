package com.singularity.todo.feature.profile.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * A profile's avatar: its emoji, or a person icon when it has none.
 *
 * The emoji field of the new-profile form is optional — only the name is
 * required — so a profile can legitimately have a blank one. A circle with an
 * empty string in it said nothing at all, and the account screen and the
 * profile switcher disagreed about what to draw. Both now call this one
 * composable, so the fallback cannot drift or be forgotten on one of them.
 *
 * The caller may override the identity colour as [backgroundColor], because the
 * two screens draw the circle on different surfaces — but it is *not* optional
 * to have a circle. It used to default to null, which meant a caller that said
 * nothing got an unbacked icon on the card's own surface: the fallback was
 * drawn, and it was the one avatar in the list without the shape the others
 * had. The default is now the same container colour the surrounding rows use,
 * so "the fallback occupies the same circle the emoji does" holds for every
 * caller, including one that passes no colour at all.
 */
@Composable
fun ProfileAvatar(
    emoji: String,
    modifier: Modifier = Modifier,
    backgroundColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
) {
    Box(
        modifier =
            modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(backgroundColor),
        contentAlignment = Alignment.Center,
    ) {
        if (emoji.isBlank()) {
            Icon(
                imageVector = Icons.Default.Person,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                text = emoji,
                style = MaterialTheme.typography.headlineSmall,
            )
        }
    }
}
