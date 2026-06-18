/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ehrocha.pulsar.R
import com.ehrocha.pulsar.transfer.PhotoTransferController
import com.ehrocha.pulsar.transport.CameraImage
import com.ehrocha.pulsar.ui.components.PulsarTopBar
import com.ehrocha.pulsar.ui.components.rememberSnackbarPoster
import com.ehrocha.pulsar.viewmodel.PulsarViewModel

/**
 * Photo-transfer gallery: a preview grid of every image on the connected
 * camera, with selection + bulk transfer to Pictures/Pulsar. Gated to the
 * content-capable transports (CCAPI / USB-PTP / PTP-IP); the Tools tile is
 * disabled otherwise, so [PulsarViewModel.contentTransport] is normally
 * non-null here.
 */
@Composable
fun PhotoTransferScreen(vm: PulsarViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val transport = remember { vm.contentTransport() }
    val postSnackbar = rememberSnackbarPoster()

    Scaffold(
        topBar = {
            PulsarTopBar(
                title = stringResource(R.string.photo_transfer_title),
                onBack = onBack,
                helpText = stringResource(R.string.photo_transfer_help),
            )
        },
    ) { pad ->
        if (transport == null) {
            EmptyStateBox(R.string.photo_transfer_unavailable, Modifier.padding(pad))
            return@Scaffold
        }
        val controller = remember(transport) {
            PhotoTransferController(transport, context.applicationContext, scope)
        }
        LaunchedEffect(transport) { controller.load() }

        // One-shot result toast.
        val result = controller.result
        LaunchedEffect(result) {
            if (result != null) {
                val msg = if (result.failed == 0) {
                    context.getString(R.string.photo_transfer_done, result.saved)
                } else {
                    context.getString(R.string.photo_transfer_done_failed, result.saved, result.failed)
                }
                postSnackbar(msg)
                controller.consumeResult()
            }
        }

        Column(Modifier.fillMaxSize().padding(pad)) {
            when {
                controller.loading -> LoadingBox(R.string.photo_transfer_loading)
                controller.loadFailed -> EmptyStateBox(R.string.photo_transfer_error, Modifier.weight(1f))
                controller.images.isEmpty() -> EmptyStateBox(R.string.photo_transfer_empty, Modifier.weight(1f))
                else -> {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(96.dp),
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentPadding = PaddingValues(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(controller.images, key = { it.id }) { img ->
                            ThumbCell(
                                image = img,
                                bitmap = controller.thumbnail(img),
                                selected = img.id in controller.selected,
                                enabled = !controller.busy,
                                onTap = { controller.toggle(img.id) },
                            )
                        }
                    }
                    TransferBar(controller)
                }
            }
        }
    }
}

@Composable
private fun ThumbCell(
    image: CameraImage,
    bitmap: ImageBitmap?,
    selected: Boolean,
    enabled: Boolean,
    onTap: () -> Unit,
) {
    val shape = RoundedCornerShape(6.dp)
    Box(
        Modifier
            .aspectRatio(1f)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .then(
                if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape)
                else Modifier
            )
            .clickable(enabled = enabled, onClick = onTap),
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = image.fileName,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Text(
                text = image.fileName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.Center).padding(4.dp),
            )
        }
        if (image.isRaw) {
            Surface(
                color = MaterialTheme.colorScheme.tertiary,
                shape = RoundedCornerShape(topEnd = 6.dp),
                modifier = Modifier.align(Alignment.BottomStart),
            ) {
                Text(
                    text = stringResource(R.string.photo_transfer_raw_badge),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onTertiary,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
        if (selected) {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(2.dp)
                    .size(20.dp)
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.surface),
            )
        }
    }
}

@Composable
private fun TransferBar(controller: PhotoTransferController) {
    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val progress = controller.transfer
            if (progress != null) {
                Text(
                    stringResource(
                        R.string.photo_transfer_progress,
                        progress.current, progress.total, progress.fileName,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                LinearProgressIndicator(
                    progress = { progress.fraction },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                val count = controller.selected.size
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (count == 0) {
                        TextButton(onClick = { controller.selectAll() }) {
                            Text(stringResource(R.string.photo_transfer_select_all))
                        }
                    } else {
                        TextButton(onClick = { controller.clearSelection() }) {
                            Text(stringResource(R.string.photo_transfer_clear))
                        }
                    }
                    Box(Modifier.weight(1f))
                    OutlinedButton(onClick = { controller.transferAll() }) {
                        Text(stringResource(R.string.photo_transfer_transfer_all))
                    }
                    Box(Modifier.size(8.dp))
                    Button(
                        onClick = { controller.transferSelected() },
                        enabled = count > 0,
                    ) {
                        Text(stringResource(R.string.photo_transfer_transfer_selected, count))
                    }
                }
            }
        }
    }
}

@Composable
private fun LoadingBox(textRes: Int) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Text(
                stringResource(textRes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

@Composable
private fun EmptyStateBox(textRes: Int, modifier: Modifier = Modifier) {
    com.ehrocha.pulsar.ui.components.EmptyState(
        icon = Icons.Default.PhotoLibrary,
        text = stringResource(textRes),
        modifier = modifier,
    )
}
