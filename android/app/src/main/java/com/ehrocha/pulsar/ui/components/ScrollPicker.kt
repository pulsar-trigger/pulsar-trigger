/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Scrollable wheel number picker with 3 visible rows.
 * Tap the centre value to type via keyboard.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ScrollPicker(
    value: Int,
    range: IntRange,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    label: String? = null,
    format: (Int) -> String = { "%02d".format(it) },
) {
    val items = remember(range) { range.toList() }
    val haptic = LocalHapticFeedback.current
    val initialIndex = (value - range.first).coerceIn(0, items.lastIndex)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialIndex)
    val itemHeight = 36.dp
    val alpha = if (enabled) 1f else 0.4f

    var editing by remember { mutableStateOf(false) }
    var editText by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

    // Sync external value → scroll position
    LaunchedEffect(value) {
        val idx = (value - range.first).coerceIn(0, items.lastIndex)
        if (!listState.isScrollInProgress && listState.firstVisibleItemIndex != idx) {
            listState.animateScrollToItem(idx)
        }
    }

    // Scroll settled → notify value change
    LaunchedEffect(listState) {
        snapshotFlow {
            listState.firstVisibleItemIndex to listState.isScrollInProgress
        }.collect { (idx, scrolling) ->
            if (!scrolling) {
                val newVal = items.getOrElse(idx) { value }
                if (newVal != value) {
                    onValueChange(newVal)
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }
            }
        }
    }

    fun commitEdit() {
        val parsed = editText.toIntOrNull()?.coerceIn(range) ?: value
        onValueChange(parsed)
        editing = false
        focusManager.clearFocus()
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier,
    ) {
        if (label != null) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
            )
            Spacer(Modifier.height(2.dp))
        }

        if (editing) {
            BasicTextField(
                value = editText,
                onValueChange = { editText = it.filter { c -> c.isDigit() } },
                textStyle = MaterialTheme.typography.titleLarge.copy(
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                ),
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { commitEdit() }),
                modifier = Modifier
                    .widthIn(min = 52.dp)
                    .height(itemHeight)
                    .focusRequester(focusRequester)
                    .onFocusChanged { if (!it.isFocused && editing) commitEdit() },
                decorationBox = { inner ->
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                                RoundedCornerShape(8.dp),
                            ),
                    ) { inner() }
                },
            )
            LaunchedEffect(Unit) { focusRequester.requestFocus() }
        } else {
            Box(
                modifier = Modifier
                    .height(itemHeight * 3)
                    .widthIn(min = 52.dp),
            ) {
                // Centre-row highlight (drawn behind the list)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(itemHeight)
                        .offset(y = itemHeight)
                        .background(
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f),
                            RoundedCornerShape(8.dp),
                        ),
                )

                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    flingBehavior = rememberSnapFlingBehavior(lazyListState = listState),
                    userScrollEnabled = enabled,
                ) {
                    // Top spacer so item 0 can sit in the centre row
                    item { Spacer(Modifier.height(itemHeight).fillMaxWidth()) }

                    items(items.size) { index ->
                        val isCenter = index == listState.firstVisibleItemIndex
                        Box(
                            modifier = Modifier
                                .height(itemHeight)
                                .fillMaxWidth()
                                .then(
                                    if (enabled && isCenter) Modifier.clickable {
                                        editText = value.toString()
                                        editing = true
                                    } else Modifier
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = format(items[index]),
                                style = if (isCenter)
                                    MaterialTheme.typography.titleLarge
                                else
                                    MaterialTheme.typography.bodyMedium,
                                color = if (isCenter)
                                    MaterialTheme.colorScheme.primary.copy(alpha = alpha)
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                        alpha = alpha * 0.4f,
                                    ),
                                textAlign = TextAlign.Center,
                            )
                        }
                    }

                    // Bottom spacer so the last item can sit in the centre row
                    item { Spacer(Modifier.height(itemHeight).fillMaxWidth()) }
                }
            }
        }
    }
}
