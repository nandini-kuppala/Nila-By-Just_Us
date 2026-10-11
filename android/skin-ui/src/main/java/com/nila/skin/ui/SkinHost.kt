package com.nila.skin.ui

import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * What a host app (Nila) tells the feature when it shows it as one of its tabs.
 *
 * Standalone, nothing is provided and every screen draws its own top bar with
 * the system insets, as before.
 */
class SkinHost(
    /** Nila's own top-bar actions (theme switch, timeline, insights, settings), shown on the feature's home. */
    val actions: @Composable RowScope.() -> Unit = {},
    /**
     * Why the camera can't open right now, or null when it can. Nila's safety
     * watch keeps the camera while it runs; taking it would stop the watch.
     */
    val cameraBusy: () -> String? = { null },
)

/** Null when the feature runs as its own app. */
val LocalSkinHost = staticCompositionLocalOf<SkinHost?> { null }
