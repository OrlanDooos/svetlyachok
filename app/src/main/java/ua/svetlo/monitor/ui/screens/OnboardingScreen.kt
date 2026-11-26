package ua.svetlo.monitor.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import ua.svetlo.monitor.data.Address
import ua.svetlo.monitor.data.OutageRepository
import ua.svetlo.monitor.data.Settings
import ua.svetlo.monitor.ui.LocalWindow
import ua.svetlo.monitor.ui.components.Pill
import ua.svetlo.monitor.ui.components.PrimaryButton
import ua.svetlo.monitor.ui.components.SCard
import ua.svetlo.monitor.ui.components.StatusOrb
import ua.svetlo.monitor.ui.theme.Status
import ua.svetlo.monitor.ui.theme.Svetlo
import ua.svetlo.monitor.ui.theme.Type

@Composable
fun OnboardingScreen(repo: OutageRepository, s: Settings, onDone: (Address) -> Unit) {
    val pager = rememberPagerState { 3 }
    val scope = rememberCoroutineScope()
    var picked by remember { mutableStateOf<Address?>(null) }
    val c = Svetlo.colors
    val w = LocalWindow.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onDone(picked ?: s.addresses.first()) }

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars).padding(horizontal = w.gutter + 4.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.widthIn(max = 560.dp).fillMaxWidth().weight(1f)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    repeat(3) { i ->
                        val width by animateDpAsState(if (i == pager.currentPage) 24.dp else 8.dp, spring(dampingRatio = 0.6f), label = "dot")
                        Box(Modifier.width(width).height(8.dp).clip(CircleShape).background(if (i == pager.currentPage) c.accent else c.line))
                    }
                }
                Text("Пропустить", style = Type.bodyBold, color = c.muted, modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { onDone(picked ?: s.addresses.first()) }.padding(10.dp))
            }
            HorizontalPager(pager, Modifier.weight(1f), userScrollEnabled = true) { page ->
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    when (page) {
                        0 -> PageWelcome()
                        1 -> PageAddress(repo, picked) { picked = it }
                        else -> PageNotifications(picked ?: s.addresses.first())
                    }
                }
            }
        }
        val label = when (pager.currentPage) { 0 -> "Начать"; 1 -> if (picked != null) "Дальше" else "Выбрать позже"; else -> "Включить уведомления" }
        PrimaryButton(label, {
            if (pager.currentPage < 2) scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
            else if (Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            else onDone(picked ?: s.addresses.first())
        }, Modifier.widthIn(max = 560.dp).fillMaxWidth().heightIn(min = 56.dp), bg = c.accent, fg = c.onAccent)
    }
}

@Composable
private fun PageWelcome() {
    val c = Svetlo.colors
    val float = rememberInfiniteTransition(label = "f").animateFloat(0f, -6f, infiniteRepeatable(tween(1600), RepeatMode.Reverse), label = "y")
    Box(Modifier.fillMaxWidth().padding(top = 24.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.graphicsLayer { translationY = float.value * density }) { StatusOrb(Status.Yellow, c.yellowSoft, Bolt, size = 190.dp) }
    }
    Text("Знайте заранее, когда будет свет", style = Type.display, color = c.ink, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    Text("Графики, аварии и блэкауты по вашему дому. Напомним до отключения и сообщим, когда свет вернётся.", style = Type.body, color = c.ink2, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        Pill("Русский", bg = c.inverse, fg = c.onInverse, border = null)
        Box(Modifier.width(8.dp))
        Pill("Українська · скоро", fg = c.muted)
    }
}

@Composable
private fun PageAddress(repo: OutageRepository, picked: Address?, onPick: (Address) -> Unit) {
    val c = Svetlo.colors
    Text("Где вы живёте?", style = Type.display, color = c.ink)
    Text("По адресу мы сами найдём вашу очередь отключений. Номер очереди знать не нужно.", style = Type.body, color = c.ink2)
    if (picked != null) {
        val pop = remember(picked) { Animatable(0.9f) }
        LaunchedEffect(picked) { pop.animateTo(1f, spring(dampingRatio = 0.45f)) }
        SCard(Modifier.fillMaxWidth().graphicsLayer { scaleX = pop.value; scaleY = pop.value }, color = c.greenSoft) {
            Text("Нашли вашу очередь", style = Type.smallBold, color = c.greenInk)
            Text(picked.street, style = Type.h2, color = c.ink)
            Text("Киев, Печерский район · очередь ${picked.queue}", style = Type.body, color = c.ink2)
        }
    }
    AddressPicker(repo, onPick)
}

@Composable
private fun PageNotifications(addr: Address) {
    val c = Svetlo.colors
    val items = listOf(
        Triple("Через 15 минут отключение", "16:00–20:00 · ${addr.label}", Status.Yellow),
        Triple("Свет вернулся", "Раньше графика на 25 минут", Status.Green),
        Triple("График изменился", "Завтра на 2 часа больше", c.accent),
    )
    Column(Modifier.fillMaxWidth().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items.forEachIndexed { i, (t, sub, col) ->
            val a = remember { Animatable(0f) }
            LaunchedEffect(Unit) { a.animateTo(1f, tween(500, delayMillis = 100 + i * 200)) }
            Row(
                Modifier.fillMaxWidth().graphicsLayer { alpha = a.value; translationY = (1f - a.value) * -20.dp.toPx() }
                    .clip(RoundedCornerShape(20.dp)).background(c.surface).padding(14.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF16181D)), contentAlignment = Alignment.Center) { Icon(Bolt, null, tint = col, modifier = Modifier.size(20.dp)) }
                Column { Text(t, style = Type.bodyBold, color = c.ink); Text(sub, style = Type.small, color = c.muted) }
            }
        }
    }
    Text("Не пропустите отключение", style = Type.display, color = c.ink)
    Text("Предупредим заранее, сообщим, когда свет вернётся, и когда облэнерго изменит график.", style = Type.body, color = c.ink2)
}
