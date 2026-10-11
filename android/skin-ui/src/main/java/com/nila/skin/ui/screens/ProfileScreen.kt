package com.nila.skin.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nila.skin.data.BabyEntity
import com.nila.skin.data.FoodLogEntity
import com.nila.skin.core.rules.Step
import com.nila.skin.ui.SkinServices
import com.nila.skin.ui.ageDays
import com.nila.skin.ui.ageText
import com.nila.skin.ui.components.NilaCard
import com.nila.skin.ui.components.OutlinePillButton
import com.nila.skin.ui.components.PillButton
import com.nila.skin.ui.components.SkinScaffold
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(baby: BabyEntity?, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(baby?.name ?: "") }
    var birth by remember { mutableStateOf(baby?.birthMs ?: (System.currentTimeMillis() - 120L * 86_400_000L)) }
    var preterm by remember { mutableStateOf(baby?.preterm ?: false) }
    var picking by remember { mutableStateOf(false) }
    SkinScaffold(title = "Your baby", onBack = onDone) {
        NilaCard(modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(name, { name = it }, label = { Text("Name or nickname") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text("Born on ${fmtDate(birth)} · ${ageText(BabyEntity(name = "", birthMs = birth).ageDays())}", style = MaterialTheme.typography.bodyMedium)
            OutlinePillButton("Change birth date", onClick = { picking = true }, icon = Icons.Outlined.CalendarMonth)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Born before 37 weeks", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Switch(preterm, { preterm = it })
            }
            Text("Age changes the rules: fever under 3 months, jaundice after 14 days (21 if born early), and which foods are safe.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        PillButton("Save", enabled = name.isNotBlank(), icon = Icons.Outlined.Check, modifier = Modifier.fillMaxWidth(), onClick = {
            scope.launch {
                SkinServices.dao.saveBaby(BabyEntity(name = name.trim(), birthMs = birth, preterm = preterm, demo = baby?.demo ?: false))
                onDone()
            }
        })
    }
    if (picking) {
        val state = rememberDatePickerState(initialSelectedDateMillis = birth)
        DatePickerDialog(onDismissRequest = { picking = false },
            confirmButton = { TextButton(onClick = { state.selectedDateMillis?.let { birth = it }; picking = false }) { Text("OK") } }) {
            DatePicker(state)
        }
    }
}

fun fmtDate(ms: Long): String = SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(ms))
fun fmtDateTime(ms: Long): String = SimpleDateFormat("d MMM, h:mm a", Locale.getDefault()).format(Date(ms))

/** What needs the parent today. */
object Today {
    suspend fun compute(baby: BabyEntity, now: Long = System.currentTimeMillis()): List<TodayItem> {
        val dao = SkinServices.dao
        val out = ArrayList<TodayItem>()
        val db = SkinServices.foods
        dao.foodsNow().filter { checkInDue(it, now) }.forEach { f ->
            out += TodayItem("Check-in: first ${db[f.foodId]?.name ?: f.foodId}", "Given ${fmtDateTime(f.timeMs)}. Any rash, swelling or vomiting since?", "food", null)
        }
        val entries = dao.allEntriesNow()
        for (c in dao.concerns().first()) {
            val last = entries.filter { it.concernId == c.id }.maxByOrNull { it.timeMs } ?: continue
            val step = runCatching { Step.valueOf(last.step) }.getOrDefault(Step.WATCH)
            if (step.ordinal >= Step.DOCTOR_SOON.ordinal) out += TodayItem(c.title, "Last check: ${step.title.lowercase()}", "concern/${c.id}", step)
            else if (now - last.timeMs > 20 * 3_600_000L && step == Step.RECHECK) out += TodayItem("Photo \"${c.title}\" again", "Same spot, same distance, in daylight", "check/${c.id}", step)
        }
        val days = baby.ageDays(now)
        val startOfDay = now - (now % 86_400_000L)
        if (days <= 14 && dao.yellowsNow().none { it.timeMs >= startOfDay }) out += TodayItem("Daily yellow check", "Eyes, palms and soles in daylight", "yellow", null)
        if (days <= 30 && dao.stoolsNow().none { it.timeMs >= startOfDay }) out += TodayItem("Nappy colour check", "Recommended daily in the first month", "stool", null)
        return out
    }

    fun checkInDue(f: FoodLogEntity, now: Long) = f.firstTime && f.checkedInMs == null && now - f.timeMs in 0..(48 * 3_600_000L)
}
