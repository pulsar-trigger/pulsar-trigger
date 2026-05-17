/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/**
 * App-wide spacing / shape / icon-size tokens. Centralised so cards rendered
 * side-by-side don't disagree on roundness or padding. Prefer these over
 * raw `.dp` literals in new UI code.
 */
object Dimens {
    /** Vertical / horizontal gaps between elements. */
    object Spacing {
        val xs = 4.dp
        val sm = 8.dp
        val md = 12.dp
        val lg = 16.dp
        val xl = 24.dp
    }

    /** Side-gutter / inner padding scale. */
    object Padding {
        val xs = 4.dp
        val sm = 8.dp
        val md = 12.dp
        val lg = 16.dp
        val xl = 20.dp
    }

    /** Material-style corner radii. Use Card for elevated containers, Sm for
     *  inline chips/cells, Lg for hero summaries / big surfaces. */
    object Radius {
        val Sm = 8.dp
        val Md = 12.dp
        val Lg = 16.dp
        val Xl = 20.dp
    }

    /** Icon sizes. Sm for inline glyphs, Md for action-row, Lg for headers,
     *  Xl for hero / launcher tiles. */
    object Icon {
        val Sm = 16.dp
        val Md = 20.dp
        val Lg = 24.dp
        val Xl = 36.dp
    }
}

/** Shorthand for the most-used card radius. */
val ShapeCard = RoundedCornerShape(Dimens.Radius.Md)
/** Shorthand for hero / summary cards. */
val ShapeHero = RoundedCornerShape(Dimens.Radius.Xl)
/** Shorthand for inline chips / inner cells. */
val ShapeChip = RoundedCornerShape(Dimens.Radius.Sm)
