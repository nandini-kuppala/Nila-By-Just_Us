package com.nila.skin.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.nila.skin.ui.screens.ConcernScreen
import com.nila.skin.ui.screens.EyeScreen
import com.nila.skin.ui.screens.FoodScreen
import com.nila.skin.ui.screens.HomeScreen
import com.nila.skin.ui.screens.HowItWorksScreen
import com.nila.skin.ui.screens.LearnScreen
import com.nila.skin.ui.screens.MealScreen
import com.nila.skin.ui.screens.ProfileScreen
import com.nila.skin.ui.screens.ReportScreen
import com.nila.skin.ui.screens.SkinCheckScreen
import com.nila.skin.ui.screens.SkinListScreen
import com.nila.skin.ui.screens.StoolScreen
import com.nila.skin.ui.screens.YellowScreen
import com.nila.skin.ui.theme.NilaTheme
import com.nila.skin.ui.theme.ThemeMode

/**
 * The whole feature as one composable, so Nila can host it behind a single
 * card ("Little Signs") with its own back stack.
 *
 * @param onExit called when the user backs out of the feature's home; the
 *   standalone app passes null.
 * @param host set when Nila shows the feature as a tab: its screens then sit
 *   inside Nila's scaffold (no second set of insets) and show Nila's actions.
 * @param dark Nila's light/dark choice, so the feature follows Nila's switch
 *   rather than the phone's setting; null follows the phone.
 * @param homeSignal bumped by the host each time its tab is tapped: the feature
 *   goes back to its home, the way tapping a tab you are already in does.
 */
@Composable
fun SkinApp(onExit: (() -> Unit)? = null, host: SkinHost? = null, dark: Boolean? = null, homeSignal: Int = 0) {
    val mode = when (dark) { true -> ThemeMode.DARK; false -> ThemeMode.LIGHT; null -> ThemeMode.SYSTEM }
    NilaTheme(mode = mode) { CompositionLocalProvider(LocalSkinHost provides host) {
        val nav = rememberNavController()
        val baby by SkinServices.dao.baby().collectAsState(initial = null)
        LaunchedEffect(homeSignal) { if (homeSignal > 0) nav.popBackStack("home", inclusive = false) }
        NavHost(nav, startDestination = "home") {
            composable("home") { HomeScreen(baby, go = { nav.navigate(it) }, onExit = onExit) }
            composable("profile") { ProfileScreen(baby, onDone = { nav.popBackStack() }) }
            composable("skin") { SkinListScreen(go = { nav.navigate(it) }, back = { nav.popBackStack() }) }
            composable("concern/{id}", listOf(navArgument("id") { type = NavType.LongType })) {
                ConcernScreen(it.arguments!!.getLong("id"), baby, go = { r -> nav.navigate(r) }, back = { nav.popBackStack() })
            }
            composable("check/{id}", listOf(navArgument("id") { type = NavType.LongType })) {
                SkinCheckScreen(it.arguments!!.getLong("id"), baby, done = { id ->
                    nav.popBackStack(); nav.navigate("concern/$id") { launchSingleTop = true }
                }, back = { nav.popBackStack() })
            }
            composable("food") { FoodScreen(baby, go = { nav.navigate(it) }, back = { nav.popBackStack() }) }
            composable("meal") { MealScreen(baby, back = { nav.popBackStack() }) }
            composable("stool") { StoolScreen(baby, back = { nav.popBackStack() }) }
            composable("eyes") { EyeScreen(baby, back = { nav.popBackStack() }) }
            composable("yellow") { YellowScreen(baby, back = { nav.popBackStack() }) }
            composable("report") { ReportScreen(baby, back = { nav.popBackStack() }) }
            composable("how") { HowItWorksScreen(back = { nav.popBackStack() }, go = { nav.navigate(it) }) }
            composable("quick") { com.nila.skin.ui.screens.QuickDemoScreen(back = { nav.popBackStack() }) }
            composable("learn") { LearnScreen(back = { nav.popBackStack() }) }
            composable("demo") { com.nila.skin.ui.screens.DemoLabScreen(go = { nav.navigate(it) }, back = { nav.popBackStack() }) }
        }
    } }
}
