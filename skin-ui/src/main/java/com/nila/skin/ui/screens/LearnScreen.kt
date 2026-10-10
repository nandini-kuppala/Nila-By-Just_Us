package com.nila.skin.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nila.skin.ui.components.InfoNote
import com.nila.skin.ui.components.MutedText
import com.nila.skin.ui.components.NilaCard
import com.nila.skin.ui.components.ScreenHero
import com.nila.skin.ui.components.SkinScaffold
import com.nila.skin.ui.theme.Tone

/**
 * Common, harmless baby skin changes.
 *
 * Education, not matching: nothing here is compared with a photo. It exists
 * because the commonest outcome of a parent noticing something on their
 * baby's skin is that it is one of these, and knowing that is what keeps a
 * notice from becoming a fright. Content follows NHS parent pages (skin rashes
 * in babies, baby acne, cradle cap, birthmarks, heat rash, nappy rash).
 */
private data class Common(val name: String, val looks: String, val usually: String, val doctorIf: String)

private val COMMON = listOf(
    Common("Milia", "Tiny white or yellow spots, mostly on the nose and cheeks.", "Clear by themselves within a few weeks. Nothing to do.", "They're still there after a few months."),
    Common("Newborn rash (erythema toxicum)", "Red blotches, sometimes with small white or yellow bumps, in the first days of life.", "Comes and goes, and is gone within about two weeks.", "Your baby seems unwell, has a fever, or the spots fill with pus."),
    Common("Baby acne", "Small red or white spots on the face, often from around two weeks of age.", "Clears within weeks to a few months. Don't squeeze them.", "It lasts beyond a few months, or there are blackheads or pus-filled spots."),
    Common("Cradle cap", "Yellow, greasy, scaly patches on the scalp.", "Gentle washing and a soft brush help; it clears within weeks to months.", "It spreads, bleeds, weeps, or seems itchy."),
    Common("Stork bite (salmon patch)", "Flat pink or red marks on the back of the neck, the eyelids or the forehead.", "Eyelid and forehead marks usually fade within a year or two.", "A mark grows, becomes raised, or changes quickly (track it in the skin diary)."),
    Common("Mongolian spots (slate-grey patches)", "Flat blue-grey patches, usually on the lower back or bottom; very common in brown-skinned babies.", "Harmless; most fade during childhood.", "Ask your doctor to note them in the health record, so they're never mistaken for bruises."),
    Common("Heat rash", "Tiny red or clear spots where the baby sweats: neck, chest, skin folds.", "Fades within a few days once the baby is kept cool.", "Your baby has a fever, or the rash doesn't settle when they're cool."),
    Common("Nappy rash", "Red, sore-looking skin in the nappy area.", "Frequent changes, nappy-free time and a barrier cream usually clear it within days.", "It hasn't improved after a week, or there are spots or pus spreading into the folds."),
)

@Composable
fun LearnScreen(back: () -> Unit) {
    SkinScaffold("Common and harmless", onBack = back) {
        ScreenHero("Most of what you'll notice is normal", "These are the everyday skin changes of the first year. Knowing them makes it easier to spot the rare thing that isn't.")
        InfoNote("This is a guide, not a match for your photo. If you're unsure, the skin diary and its checklist will tell you when to see a doctor.", icon = Icons.Outlined.FavoriteBorder, tone = Tone.TEAL)
        COMMON.forEach { c ->
            NilaCard(modifier = Modifier.fillMaxWidth(), verticalSpacing = 6.dp) {
                Text(c.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Looks like: ${c.looks}", style = MaterialTheme.typography.bodyMedium)
                    Text("Usually: ${c.usually}", style = MaterialTheme.typography.bodyMedium)
                    Text("See a doctor if: ${c.doctorIf}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        MutedText("Based on NHS parent guidance on skin rashes in babies, baby acne, cradle cap, birthmarks, heat rash and nappy rash.")
    }
}
