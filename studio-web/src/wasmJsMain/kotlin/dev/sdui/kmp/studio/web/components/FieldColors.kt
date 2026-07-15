package dev.sdui.kmp.studio.web.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable

/**
 * Shared `OutlinedTextField` colors for the Studio: filled dark input wells with hairline
 * resting borders and an accent focus ring. Used by login, audit filters, experiments
 * filters, and the inspector so every input reads identically.
 */
@Composable
internal fun studioFieldColors(): TextFieldColors = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = MaterialTheme.colorScheme.primary,
    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
    errorContainerColor = MaterialTheme.colorScheme.surfaceContainer,
    cursorColor = MaterialTheme.colorScheme.primary,
)
