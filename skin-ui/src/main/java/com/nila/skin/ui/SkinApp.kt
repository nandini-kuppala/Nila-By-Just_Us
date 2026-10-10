package com.nila.skin.ui

import androidx.compose.runtime.Composable
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
import com.nila.skin.ui.screens.GlassTestScreen
import com.nila.skin.ui.screens.HomeScreen
import com.nila.skin.ui.screens.HowItWorksScreen
import com.nila.skin.ui.screens.MealScreen
import com.nila.skin.ui.screens.ProfileScreen
import com.nila.skin.ui.screens.ReportScreen
import com.nila.skin.ui.screens.SkinCheckScreen
import com.nila.skin.ui.screens.SkinListScreen
import com.nila.skin.ui.screens.StoolScreen
import com.nila.skin.ui.screens.YellowScreen
import com.nila.skin.ui.theme.NilaTheme

/**
 * The whole feature as one composable, so Nila can host it behind a single
 * card ("Little Signs") with its own back stack.
 *
 * @param onExit called when the user backs out of the feature's home; the
 *   standalone app passes null.
 */
@Composable
fun SkinApp(onExit: (() -> Unit)? = null) {
    NilaTheme {
        val nav = rememberNavController()
        val baby by SkinServices.dao.baby().collectAsState(initial = null)
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
            composable("glass") { GlassTestScreen(go = { nav.navigate(it) }, back = { nav.popBackStack() }) }
            composable("report") { ReportScreen(baby, back = { nav.popBackStack() }) }
            composable("how") { HowItWorksScreen(back = { nav.popBackStack() }) }
        }
    }
}
