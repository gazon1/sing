package com.singularity.todo.core.ui.components.sheet

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction

/**
 * The search field a picker sheet uses to narrow its list.
 *
 * One field, two pickers: the tag picker and the related-task picker both filter
 * as the query changes, and the clear action is what restores the full list. The
 * field owns neither the query nor the list — the sheet does.
 */
@Composable
fun PickerSearchField(
    queryState: TextFieldState,
    placeholder: String,
    focusRequester: FocusRequester,
    testTag: String,
    clearTestTag: String,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        state = queryState,
        modifier =
            modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .testTag(testTag),
        placeholder = { Text(placeholder) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (queryState.text.isNotEmpty()) {
                IconButton(
                    onClick = { queryState.clearText() },
                    modifier = Modifier.testTag(clearTestTag),
                ) {
                    Icon(Icons.Filled.Close, contentDescription = "Clear query")
                }
            }
        },
        lineLimits = TextFieldLineLimits.SingleLine,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
    )
}
