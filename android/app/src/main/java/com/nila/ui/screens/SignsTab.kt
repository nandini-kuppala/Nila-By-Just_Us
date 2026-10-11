package com.nila.ui.screens

import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.nila.monitor.WatchService
import com.nila.signs.SignsVision
import com.nila.skin.data.BabyEntity
import com.nila.ui.AppState
import com.nila.skin.ui.SkinApp
import com.nila.skin.ui.SkinHost
import com.nila.skin.ui.SkinServices
import com.nila.ui.theme.LocalNilaDarkTheme

/**
 * The Signs tab: Little Signs, the diary for the small changes parents notice
 * between check-ups (a mark, the nappy, the eyes, a food), built and tested as
 * its own app on the little-changes branch and hosted here unchanged.
 *
 * Nila supplies three things: its vision model ([SignsVision], shared with the
 * watch, never a second copy on the NPU), its light/dark choice, and the rule
 * that the safety watch keeps the camera while it runs.
 */
@Composable
fun SignsTab(state: AppState, homeSignal: Int, actions: @Composable RowScope.() -> Unit) {
    val context = LocalContext.current
    // Idempotent; the first visit opens its database and photo store.
    remember { SkinServices.init(context, vision = SignsVision(context)); true }
    // Nila already knows the baby: Little Signs starts with that profile rather
    // than asking again. Only when it has none; a profile set there is never replaced.
    val baby by state.baby.collectAsState()
    LaunchedEffect(baby) {
        val b = baby ?: return@LaunchedEffect
        if (b.name.isNotBlank() && b.birthDateMs > 0 && SkinServices.dao.babyNow() == null) {
            SkinServices.dao.saveBaby(BabyEntity(name = b.name, birthMs = b.birthDateMs))
        }
    }
    val latestActions by rememberUpdatedState(actions)
    val host = remember {
        SkinHost(
            actions = { latestActions(this) },
            cameraBusy = {
                if (WatchService.state.value.running)
                    "The safety watch is using the camera. Stop it on the Watch tab, or choose a photo from your gallery."
                else null
            },
        )
    }
    SkinApp(host = host, dark = LocalNilaDarkTheme.current, homeSignal = homeSignal)
}
