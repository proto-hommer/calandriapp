package io.github.protohommer.meshpaper.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.protohommer.meshpaper.MeshViewModel
import io.github.protohommer.meshpaper.mesh.ChatMsg
import io.github.protohommer.meshpaper.mesh.Conv
import io.github.protohommer.meshpaper.mesh.MAX_TEXT_BYTES
import io.github.protohommer.meshpaper.mesh.MsgStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Nombre visible de una conversación. */
fun convTitle(vm: MeshViewModel, key: String): String = when (val c = Conv.parse(key)) {
    is Conv.Channel -> {
        val ch = vm.channels.value[c.index]
        val name = ch?.settings?.name.orEmpty().ifEmpty { "LongFast" }
        when {
            c.index == 0 && vm.hasPrivateGroup() -> "Grupo: $name"
            c.index == 0 -> "Canal principal ($name)"
            else -> "Canal $name"
        }
    }
    is Conv.Direct -> {
        val n = vm.nodes.value[c.num]
        "DM: " + (n?.longName?.ifEmpty { null } ?: n?.shortName?.ifEmpty { null } ?: "!%08x".format(c.num))
    }
    null -> key
}

/** Lista de conversaciones: los canales activos primero, después los DM con mensajes. */
@Composable
fun ChatsScreen(vm: MeshViewModel, onOpen: (String) -> Unit, onBack: () -> Unit) {
    val msgs by vm.messages.collectAsState()
    val channels by vm.channels.collectAsState()
    val lastRead by vm.lastRead.collectAsState()
    vm.nodes.collectAsState().value // recomponer cuando llegan nombres

    val channelKeys = channels.keys.sorted().map { Conv.Channel(it).key }
    val dmKeys = msgs.map { it.conv }.filter { it.startsWith("dm:") }.distinct()
        .sortedByDescending { k -> msgs.lastOrNull { it.conv == k }?.time ?: 0 }
    val keys = channelKeys + dmKeys + msgs.map { it.conv }.distinct().filter { it !in channelKeys && it !in dmKeys }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        TextButton(onClick = onBack) { Text("← Volver") }
        Text("Mensajes", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.padding(4.dp))
        LazyColumn {
            items(keys, key = { it }) { k ->
                val last = msgs.lastOrNull { it.conv == k }
                val unread = msgs.count { it.conv == k && !it.mine && it.time > (lastRead[k] ?: 0) }
                Row(
                    Modifier.fillMaxWidth().clickable { onOpen(k) }.padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(convTitle(vm, k), fontWeight = FontWeight.Bold)
                        Text(
                            last?.let { (if (it.mine) "Vos: " else "") + it.text } ?: "Sin mensajes",
                            style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (unread > 0) Badge { Text("$unread") }
                }
                HorizontalDivider()
            }
        }
        Spacer(Modifier.padding(4.dp))
        Text(
            "Para un mensaje directo, tocá un nodo en la lista.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
fun ChatScreen(vm: MeshViewModel, conv: String, onBack: () -> Unit) {
    val all by vm.messages.collectAsState()
    val nodes by vm.nodes.collectAsState()
    val msgs = all.filter { it.conv == conv }
    val list = rememberLazyListState()
    var text by rememberSaveable(conv) { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val isChannel = conv.startsWith("ch:")

    LaunchedEffect(msgs.size) {
        vm.markRead(conv)
        if (msgs.isNotEmpty()) list.animateScrollToItem(msgs.size - 1)
    }

    Column(Modifier.fillMaxSize().imePadding().padding(horizontal = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("←") }
            Text(convTitle(vm, conv), style = MaterialTheme.typography.titleMedium, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(msgs, key = { "${it.from}:${it.id}" }) { m ->
                val sender = if (m.mine) null else nodes[m.from]?.let { n ->
                    n.shortName.ifEmpty { null } ?: n.longName.ifEmpty { null }
                } ?: "!%08x".format(m.from)
                Bubble(m, if (isChannel) sender else null, onRetry = { vm.retry(m) })
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        val bytes = text.trim().toByteArray(Charsets.UTF_8).size
        Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it; error = null },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Mensaje") },
                supportingText = if (bytes > MAX_TEXT_BYTES * 3 / 4) {
                    { Text("$bytes/$MAX_TEXT_BYTES") }
                } else null,
                isError = bytes > MAX_TEXT_BYTES,
                maxLines = 4,
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = {
                    val why = vm.sendText(conv, text)
                    if (why == null) text = "" else error = why
                },
                enabled = text.isNotBlank() && bytes <= MAX_TEXT_BYTES,
            ) { Text("Enviar") }
        }
    }
}

private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

@Composable
private fun Bubble(m: ChatMsg, sender: String?, onRetry: () -> Unit) {
    val bg = if (m.mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (m.mine) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Box(Modifier.fillMaxWidth(), contentAlignment = if (m.mine) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(
            Modifier.widthIn(max = 300.dp)
                .background(bg, RoundedCornerShape(12.dp))
                .then(if (m.status == MsgStatus.FAILED) Modifier.clickable(onClick = onRetry) else Modifier)
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            sender?.let { Text(it, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = fg) }
            Text(m.text, color = fg)
            val status = when (m.status) {
                MsgStatus.RECEIVED -> ""
                MsgStatus.SENDING -> if (m.attempt > 1) " · reintentando…" else " · enviando…"
                MsgStatus.RELAYED -> if (m.to == -1) " · ✓ salió" else " · ✓ repetido"
                MsgStatus.DELIVERED -> " · ✓✓ entregado"
                MsgStatus.FAILED -> " · ✗ ${m.error ?: "falló"} · tocá para reintentar"
            }
            Text(
                timeFmt.format(Date(m.time * 1000)) + status,
                style = MaterialTheme.typography.labelSmall,
                color = if (m.status == MsgStatus.FAILED) MaterialTheme.colorScheme.error else fg.copy(alpha = 0.7f),
            )
        }
    }
}
