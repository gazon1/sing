package com.singularity.todo.feature.settings.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.settings.SettingsIntent
import com.singularity.todo.core.settings.SettingsSection
import com.singularity.todo.core.settings.ThemeMode
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.components.ColorSwatch
import com.singularity.todo.core.ui.components.SettingsActionRow
import com.singularity.todo.core.ui.components.SettingsSection
import com.singularity.todo.core.ui.formatDecimal
import com.singularity.todo.core.ui.onboarding.OnboardingSettingsRepository
import com.singularity.todo.core.ui.preview.PreviewThemed
import com.singularity.todo.core.ui.theme.LocalIsDarkTheme
import com.singularity.todo.core.ui.theme.SingularityAccents
import com.singularity.todo.core.ui.theme.accentPrimaryColor
import com.singularity.todo.feature.notes.Note
import com.singularity.todo.feature.notes.NoteId
import com.singularity.todo.feature.notes.components.NoteCardContent
import com.singularity.todo.feature.settings.SettingsUiState
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import com.singularity.todo.feature.tasks.presentation.components.list.TaskRowContent
import com.singularity.todo.feature.tasks.presentation.model.TaskUi
import kotlinx.coroutines.launch
import kotlin.time.Instant

@Composable
fun InterfaceSettingsScreen(
    state: SettingsUiState.Content,
    onIntent: (SettingsIntent) -> Unit,
    modifier: Modifier = Modifier,
    onboarding: OnboardingSettingsRepository? = null,
) {
    val scope = rememberCoroutineScope()
    Column(
        modifier =
            modifier
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SettingsSection(title = "Preview") {
            ThemePreview()
        }

        SettingsSection(title = "Theme") {
            ThemeModeSelector(
                selected = state.appearance.themeMode,
                onSelect = { onIntent(SettingsIntent.Appearance.UpdateThemeMode(it)) },
            )
        }

        AccentColorPicker(
            selected = SingularityAccents.fromString(state.appearance.accentColor),
            onSelect = { onIntent(SettingsIntent.Appearance.UpdateAccentColor(it.name.lowercase())) },
        )

        FontSizeSlider(
            value = state.appearance.fontSizeScale,
            onValueChange = { onIntent(SettingsIntent.Appearance.UpdateFontSizeScale(it)) },
        )

        // The tour shows itself once; this is the "show me again" from REQ-4, and it is
        // what keeps "show once" from being a one-way door the user cannot undo.
        //
        // Rendered only when a store is supplied rather than defaulting to `koinInject()`:
        // a default would run during previews, which have no Koin graph, and would take
        // the whole preview down with a resolution error instead of degrading.
        if (onboarding != null) {
            SettingsSection(title = "Help") {
                SettingsActionRow(
                    title = "Show tips again",
                    subtitle = "Replays the short tour of the agenda screen",
                    onClick = {
                        scope.launch { onboarding.resetSpotlight() }
                    },
                    testTag = TestTags.Onboarding.REPLAY_TUTORIAL_ROW,
                )
            }
        }
    }
}

/**
 * What the selected theme, accent and font scale actually look like.
 *
 * Drawn with the production components on the ambient `SingularityTheme`, so it
 * cannot disagree with the app: the note is rendered selected, which is the
 * `primaryContainer` role the accent paints. Reusing the components also means
 * the preview shows the truth about them — the task row's title is a fixed
 * `16.sp` and does not follow the font-scale setting, while the note's typography
 * does.
 *
 * Hidden from assistive tech: the checkbox is not a control and the note is not a
 * note. It stays in the semantics tree, so tests can still assert on it.
 */
@Composable
private fun ThemePreview() {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .semantics { hideFromAccessibility() },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // `enabled = false` is what keeps the sample out of the desktop focus order —
        // a disabled toggleable takes neither focus nor input.
        TaskRowContent(task = themePreviewTask, onToggleCompleted = {}, enabled = false)
        NoteCardContent(note = themePreviewNote, isSelected = true)
    }
}

private val themePreviewTask =
    TaskUi(
        id = TaskId("theme-preview-task"),
        title = "Sample task",
        project = "Sample project",
        dueLabel = "Today",
        priority = TaskPriority.Medium,
    )

private val themePreviewNote =
    Note(
        id = NoteId("theme-preview-note"),
        userId = UserId.anonymous,
        title = "Sample note",
        bodyHtml = "<p>The accent tints a selected note.</p>",
        createdAt = Instant.fromEpochMilliseconds(0),
        updatedAt = Instant.fromEpochMilliseconds(0),
    )

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AccentColorPicker(
    selected: SingularityAccents,
    onSelect: (SingularityAccents) -> Unit,
) {
    SettingsSection(title = "Accent Color") {
        // Swatches row
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SingularityAccents.entries.forEach { accent ->
                AccentSwatch(
                    accent = accent,
                    selected = accent == selected,
                    onClick = { onSelect(accent) },
                    testTag = TestTags.Settings.accentSwatch(accent.name),
                )
            }
        }
    }
}

@Composable
private fun AccentSwatch(
    accent: SingularityAccents,
    selected: Boolean,
    onClick: () -> Unit,
    testTag: String,
) {
    // The swatch shows the colour the app will paint, not the seed it derives from:
    // the palette desaturates the seed, so a seed-coloured swatch promises a colour
    // the user never sees.
    val isDark = LocalIsDarkTheme.current
    val swatchColor = remember(accent, isDark) { accentPrimaryColor(accent = accent, isDark = isDark) }
    ColorSwatch(
        color = swatchColor,
        name = accent.displayName,
        selected = selected,
        onClick = onClick,
        size = 40.dp,
        modifier = Modifier.testTag(testTag),
    ) {
        if (selected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun FontSizeSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
) {
    SettingsSection(title = "Font Size") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Scale", style = MaterialTheme.typography.bodyMedium)
            Text(formatDecimal((value * 100).toDouble(), 0) + "%", style = MaterialTheme.typography.bodyMedium)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = 0.75f..1.5f,
            steps = 5,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .testTag(TestTags.Settings.FONT_SIZE_SLIDER),
        )
        // Live preview sample — the theme already applies the scale to its type
        // scale, so this reads the scaled slot instead of multiplying again.
        Text(
            "Sample task text",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Three-way segmented button for theme mode selection (System / Light / Dark). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThemeModeSelector(
    selected: ThemeMode,
    onSelect: (ThemeMode) -> Unit,
) {
    val modes = listOf(ThemeMode.System, ThemeMode.Light, ThemeMode.Dark)
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        modes.forEachIndexed { index, mode ->
            SegmentedButton(
                selected = selected == mode,
                onClick = { onSelect(mode) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                label = { Text(themeModeLabel(mode)) },
                modifier = Modifier.testTag(TestTags.Settings.themeModeButton(mode.name)),
            )
        }
    }
}

private fun themeModeLabel(mode: ThemeMode): String =
    when (mode) {
        ThemeMode.System -> "System"
        ThemeMode.Light -> "Light"
        ThemeMode.Dark -> "Dark"
    }

// ===== Preview =====

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun InterfaceSettingsScreenLightPreview() =
    PreviewThemed(darkTheme = false, useSurface = false) {
        InterfaceSettingsScreen(
            state =
                SettingsUiState.Content(
                    appearance =
                        SettingsSection.Appearance(
                            themeMode = ThemeMode.Light,
                            accentColor = SingularityAccents.Blue.name.lowercase(),
                            fontSizeScale = 1.0f,
                        ),
                ),
            onIntent = {},
        )
    }

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun InterfaceSettingsScreenDarkPreview() =
    PreviewThemed(darkTheme = true, fontSizeScale = 1.25f, useSurface = false) {
        InterfaceSettingsScreen(
            state =
                SettingsUiState.Content(
                    appearance =
                        SettingsSection.Appearance(
                            themeMode = ThemeMode.Dark,
                            accentColor = SingularityAccents.Purple.name.lowercase(),
                            fontSizeScale = 1.25f,
                        ),
                ),
            onIntent = {},
        )
    }
