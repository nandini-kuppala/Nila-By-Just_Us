package com.nila.skin.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nila.skin.ui.theme.Tone
import com.nila.skin.ui.theme.urgentAccent
import com.nila.skin.ui.theme.toneColors

/**
 * The parts every screen is built from.
 *
 * The revamp replaced a dozen slightly different hand-rolled boxes with these,
 * and the rules they encode are the look of the app:
 *
 * - **One card.** White on a barely-tinted page, a hairline border, 18dp corners.
 *   No shadows: a screen with ten elevated cards is a screen of shadows.
 * - **Colour in small doses.** A pale circle behind an icon, a faintly tinted
 *   tile. Identity colour comes from [Tone]; severity colour stays where it was,
 *   in the severity palette, so red still means something at 3am.
 * - **Pills for actions.** A full-width primary action is a rounded pill with an
 *   icon, so it reads as the one thing to do on a card.
 */

/** Corner radius of a card. Kept in one place so every surface agrees. */
val CardRadius = 18.dp
val CardShape = RoundedCornerShape(CardRadius)

/**
 * The standard card.
 *
 * @param tone a faint tinted ground instead of white, for tiles that carry an
 *   identity (the cry tile, the sleep tile).
 * @param onClick makes the whole card a target. Rows inside a card that are
 *   targets of their own use [ListRow] instead.
 */
@Composable
fun NilaCard(
    modifier: Modifier = Modifier,
    tone: Tone? = null,
    borderColor: Color? = null,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    onClick: (() -> Unit)? = null,
    verticalSpacing: Dp = 12.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val ground = tone?.let { toneColors(it).wash } ?: scheme.surfaceContainerLowest
    val edge = borderColor
        ?: tone?.let { toneColors(it).container }
        ?: scheme.outlineVariant
    Column(
        modifier = modifier
            .clip(CardShape)
            .background(ground)
            .border(1.dp, edge, CardShape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(verticalSpacing),
        content = content,
    )
}

/** An icon on a pale circle: the app's main carrier of identity colour. */
@Composable
fun IconBadge(
    icon: ImageVector,
    tone: Tone,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    iconSize: Dp = size * 0.5f,
    contentDescription: String? = null,
) {
    val colors = toneColors(tone)
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(colors.container),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = colors.ink,
             modifier = Modifier.size(iconSize))
    }
}

/**
 * A section heading, with an optional count and an optional action on the right.
 *
 * The action is a quiet text link ("See all"), never a button: a heading with a
 * button beside it competes with the cards underneath for the eye.
 */
@Composable
fun SectionTitle(
    text: String,
    modifier: Modifier = Modifier,
    count: Int? = null,
    action: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    onAction: (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = 32.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (count != null) "$text  ($count)" else text,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = scheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
        if (action != null && onAction != null) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(onClick = onAction)
                    .padding(start = 8.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(action, style = MaterialTheme.typography.labelLarge,
                     color = scheme.primary)
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null,
                     tint = scheme.primary, modifier = Modifier.size(20.dp))
            }
        }
    }
}

/** The one primary action on a card: a filled teal pill. */
@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val dark = com.nila.skin.ui.theme.LocalNilaDarkTheme.current
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 52.dp),
        shape = CircleShape,
        contentPadding = PaddingValues(horizontal = 22.dp, vertical = 12.dp),
        colors = if (dark) ButtonDefaults.buttonColors(
            containerColor = com.nila.skin.ui.theme.ButtonFillDark,
            contentColor = Color.White,
        ) else ButtonDefaults.buttonColors(),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(text, style = MaterialTheme.typography.titleSmall,
             fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/**
 * A secondary action: an outlined pill.
 *
 * @param danger draws it in the urgent red, for the one control that stops a
 *   watch. Red because stopping a monitor is the action with a cost.
 */
@Composable
fun OutlinePillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    danger: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    val ink = if (danger) urgentAccent()
              else scheme.primary
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 50.dp),
        shape = CircleShape,
        border = BorderStroke(1.5.dp, if (enabled) ink.copy(alpha = 0.75f)
                                       else scheme.outlineVariant),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = ink),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(19.dp))
            Spacer(Modifier.width(9.dp))
        }
        Text(text, style = MaterialTheme.typography.titleSmall,
             fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/**
 * A caveat or a fact about the app, with an info glyph.
 *
 * The successor to the bare "i" box. Same job -- present wherever a claim has a
 * limit -- on a softer ground with a real icon, so it reads as part of the
 * design rather than as a footnote somebody forgot to style.
 */
@Composable
fun InfoNote(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Outlined.Info,
    tone: Tone = Tone.SLATE,
    title: String? = null,
) {
    val colors = toneColors(tone)
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(colors.wash)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(icon, contentDescription = null, tint = colors.ink,
             modifier = Modifier.padding(top = 1.dp).size(20.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            title?.let {
                Text(it, style = MaterialTheme.typography.titleSmall,
                     fontWeight = FontWeight.SemiBold, color = scheme.onSurface)
            }
            Text(text, style = MaterialTheme.typography.bodySmall,
                 color = scheme.onSurfaceVariant)
        }
    }
}

/**
 * A short label on a tinted pill: "On device", "Live", "Up to date".
 *
 * @param dot a leading dot in the ink colour, for a state ("Live").
 */
@Composable
fun TonePill(
    text: String,
    tone: Tone,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    dot: Boolean = false,
) {
    val colors = toneColors(tone)
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(colors.container)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (dot) Box(Modifier.size(8.dp).clip(CircleShape).background(colors.ink))
        icon?.let {
            Icon(it, contentDescription = null, tint = colors.ink, modifier = Modifier.size(16.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, color = colors.ink,
             maxLines = 1)
    }
}

/**
 * A tappable row: badge, title, subtitle, and a chevron or anything else on the
 * right. The building block of every list in the app.
 */
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    tone: Tone = Tone.TEAL,
    badgeSize: Dp = 44.dp,
    onClick: (() -> Unit)? = null,
    chevron: Boolean = onClick != null,
    subtitleLines: Int = 2,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        icon?.let { IconBadge(it, tone, size = badgeSize) }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall,
                 fontWeight = FontWeight.SemiBold, color = scheme.onSurface)
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodySmall,
                     color = scheme.onSurfaceVariant, maxLines = subtitleLines,
                     overflow = TextOverflow.Ellipsis)
            }
        }
        trailing?.invoke(this)
        if (chevron) {
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null,
                 tint = scheme.onSurfaceVariant)
        }
    }
}

/**
 * Two or three mutually exclusive options as one rounded track, the chosen one
 * on a pale teal pill. Used for Baby / Mother.
 */
@Composable
fun SegmentedPills(
    options: List<Pair<String, ImageVector?>>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .background(scheme.surfaceContainerLow)
            .border(1.dp, scheme.outlineVariant, CircleShape)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEachIndexed { i, (label, icon) ->
            val on = i == selected
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(CircleShape)
                    .background(if (on) scheme.primaryContainer else Color.Transparent)
                    .clickable { onSelect(i) }
                    .padding(vertical = 11.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                icon?.let {
                    Icon(it, contentDescription = null,
                         tint = if (on) scheme.primary else scheme.onSurfaceVariant,
                         modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                }
                Text(label, style = MaterialTheme.typography.titleSmall,
                     fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                     color = if (on) scheme.primary else scheme.onSurfaceVariant)
            }
        }
    }
}

/** A row of choice pills that scrolls sideways when it has to: date ranges. */
@Composable
fun ChoicePills(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val dark = com.nila.skin.ui.theme.LocalNilaDarkTheme.current
    val fill = if (dark) com.nila.skin.ui.theme.ButtonFillDark else scheme.primary
    val onFill = if (dark) Color.White else scheme.onPrimary
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(if (on) fill else scheme.surfaceContainerLow)
                    .border(1.dp, if (on) fill else scheme.outlineVariant, CircleShape)
                    .clickable { onSelect(i) }
                    .padding(horizontal = 15.dp, vertical = 10.dp),
            ) {
                Text(label, style = MaterialTheme.typography.labelLarge,
                     fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                     color = if (on) onFill else scheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * The opening of a screen: a large title, a sentence, and a spot illustration on
 * the right. Sits directly on the page rather than in a card.
 */
@Composable
fun ScreenHero(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    art: (@Composable BoxScope.() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f).padding(end = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.headlineLarge,
                 color = scheme.onSurface)
            Text(body, style = MaterialTheme.typography.bodyMedium,
                 color = scheme.onSurfaceVariant)
        }
        if (art != null) {
            Box(modifier = Modifier.size(width = 118.dp, height = 112.dp),
                contentAlignment = Alignment.Center, content = art)
        }
    }
}

/** A thin rule with a word in the middle: "OR". */
@Composable
fun OrDivider(word: String = "OR", modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).height(1.dp).background(scheme.outlineVariant))
        Text(word, style = MaterialTheme.typography.labelMedium,
             color = scheme.onSurfaceVariant,
             modifier = Modifier.padding(horizontal = 14.dp))
        Box(Modifier.weight(1f).height(1.dp).background(scheme.outlineVariant))
    }
}

/** A hairline between rows inside a card. */
@Composable
fun RowDivider(modifier: Modifier = Modifier, inset: Dp = 0.dp) {
    Box(
        modifier
            .padding(start = inset)
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant)
    )
}

/**
 * A highlighted one-line takeaway under a chart: tinted strip, icon, sentence.
 * "2 days were above the 3h threshold."
 */
@Composable
fun TakeawayStrip(
    text: String,
    tone: Tone,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val colors = toneColors(tone)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(colors.container.copy(alpha = 0.7f))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = colors.ink, modifier = Modifier.size(22.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium,
             color = colors.ink, modifier = Modifier.weight(1f))
        if (onClick != null) {
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null,
                 tint = colors.ink)
        }
    }
}
