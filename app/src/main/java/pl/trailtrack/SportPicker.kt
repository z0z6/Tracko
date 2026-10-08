package pl.trailtrack

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Wybór aktywności; w trakcie nagrywania widoczna tylko aktualna. */
@Composable
fun SportPicker(selected: Sport, enabled: Boolean, onSelect: (Sport) -> Unit) {
    val c = ios()
    val onAccent = if (c.blue.luminance() > 0.5f) Color.Black else Color.White
    val list = if (enabled) Sport.values().toList() else listOf(selected)
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        for (sp in list) {
            val sel = sp == selected
            val shape = RoundedCornerShape(50)
            Row(
                Modifier.clip(shape)
                    .background(if (sel) c.blue else c.card)
                    .border(1.dp, if (sel) c.blue else c.separator, shape)
                    .clickable(enabled = enabled) { onSelect(sp) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AppIconView(sp.icon, if (sel) onAccent else c.label, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(sp.label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (sel) onAccent else c.label, maxLines = 1)
            }
        }
    }
}

