package dev.minicam.mobile.ui.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.minicam.mobile.data.NotificationSchedule
import dev.minicam.mobile.data.NotificationScheduleStore
import dev.minicam.mobile.ui.theme.CamColors
import dev.minicam.mobile.ui.theme.CamDimens

private const val STEP_MINUTES = 30

private val timeOptions: List<Pair<Int, String>> =
    (0 until NotificationSchedule.MINUTES_PER_DAY step STEP_MINUTES).map { it to NotificationSchedule.formatMinute(it) }

/**
 * "Schedule on this phone" (docs/adr/0015): days and an hour range in which this phone shows
 * detection pushes. Saved on every change; it only filters pushes on this phone.
 */
@Composable
fun NotificationScheduleBlock(store: NotificationScheduleStore) {
    var schedule by remember { mutableStateOf(store.load()) }

    fun update(change: (NotificationSchedule) -> NotificationSchedule) {
        schedule = change(schedule).also(store::save)
    }

    HorizontalDivider(color = CamColors.Border)
    Text("Schedule on this phone", color = CamColors.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    LabeledSwitch("Only notify on these days and hours", schedule.enabled) { on -> update { it.copy(enabled = on) } }
    if (schedule.enabled) {
        DayChips(schedule.days) { days -> update { it.copy(days = days) } }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { update { it.copy(days = NotificationSchedule.WEEKDAYS) } }) {
                Text("Mon–Fri", color = CamColors.AccentHover, fontSize = 13.sp)
            }
            TextButton(onClick = { update { it.copy(days = NotificationSchedule.EVERY_DAY) } }) {
                Text("Every day", color = CamColors.AccentHover, fontSize = 13.sp)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.weight(1f)) {
                OptionPicker("From", timeOptions, schedule.startMinute) { m -> update { it.copy(startMinute = m) } }
            }
            Box(Modifier.weight(1f)) {
                OptionPicker("To", timeOptions, schedule.endMinute) { m -> update { it.copy(endMinute = m) } }
            }
        }
        if (schedule.days.isEmpty()) {
            Text("Pick at least one day, or no detection will be shown.", color = CamColors.Danger, fontSize = 12.sp)
        } else {
            Hint("Notifies ${schedule.summary}, in this phone's time zone.")
        }
    }
    Hint(
        "Applies to every camera, only on this phone. A range that ends before it starts runs past midnight " +
            "(8:00 PM – 6:00 AM on Mon–Fri covers Friday night until Saturday 6:00 AM).",
    )
}

/** Seven round toggles, Monday first. */
@Composable
private fun DayChips(days: Set<Int>, onChange: (Set<Int>) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        NotificationSchedule.ALL_DAYS.forEach { day ->
            val selected = day in days
            val shape = RoundedCornerShape(CamDimens.Radius)
            Box(
                Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clip(shape)
                    .background(if (selected) CamColors.Accent else CamColors.Bg)
                    .border(1.dp, if (selected) CamColors.Accent else CamColors.Border, shape)
                    .clickable(role = Role.Checkbox) { onChange(if (selected) days - day else days + day) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    NotificationSchedule.dayLabel(day).take(2),
                    color = if (selected) CamColors.OnAccent else CamColors.Muted,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}
