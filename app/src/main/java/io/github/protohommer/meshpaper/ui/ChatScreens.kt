package io.github.protohommer.meshpaper.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.combinedClickable
import kotlinx.coroutines.launch
import io.github.protohommer.meshpaper.ui.theme.HudCyan
import io.github.protohommer.meshpaper.ui.theme.SigFair
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
import androidx.compose.ui.graphics.Color
import io.github.protohommer.meshpaper.ui.theme.HudBg
import io.github.protohommer.meshpaper.ui.theme.HudTextDim
import io.github.protohommer.meshpaper.ui.theme.HudText
import io.github.protohommer.meshpaper.ui.theme.HudLine
import io.github.protohommer.meshpaper.ui.theme.HudSurface
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import io.github.protohommer.meshpaper.MeshViewModel
import io.github.protohommer.meshpaper.mesh.ChatMsg
import io.github.protohommer.meshpaper.mesh.Conv
import io.github.protohommer.meshpaper.mesh.MAX_TEXT_BYTES
import io.github.protohommer.meshpaper.mesh.MsgStatus
import io.github.protohommer.meshpaper.mesh.hopsText
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Nombre visible de una conversación. */
fun convTitle(vm: MeshViewModel, key: String): String = when (val c = Conv.parse(key)) {
    is Conv.Channel -> {
        val ch = vm.channels.value[c.index]
        val name = ch?.settings?.name.orEmpty().ifEmpty { "LongFast" }
        when {
            vm.groupName(c.index) != null -> "Grupo: $name"
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
fun ChatsScreen(vm: MeshViewModel, onOpen: (String) -> Unit) {
    val msgs by vm.messages.collectAsState()
    val channels by vm.channels.collectAsState()
    val lastRead by vm.lastRead.collectAsState()
    vm.nodes.collectAsState().value // recomponer cuando llegan nombres

    val channelKeys = channels.keys.sorted().map { Conv.Channel(it).key }
    val dmKeys = msgs.map { it.conv }.filter { it.startsWith("dm:") }.distinct()
        .sortedByDescending { k -> msgs.lastOrNull { it.conv == k }?.time ?: 0 }
    val keys = channelKeys + dmKeys + msgs.map { it.conv }.distinct().filter { it !in channelKeys && it !in dmKeys }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        HudHeader("Mensajes")
        LazyColumn(Modifier.weight(1f)) {
            items(keys, key = { it }) { k ->
                val last = msgs.lastOrNull { it.conv == k }
                val unread = msgs.count { it.conv == k && !it.mine && it.time > (lastRead[k] ?: 0) }
                val isDm = k.startsWith("dm:")
                Row(
                    Modifier.fillMaxWidth().clickable { onOpen(k) }.padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.width(3.dp).height(34.dp).background(if (isDm) SigFair else HudCyan))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(convTitle(vm, k), fontWeight = FontWeight.SemiBold)
                        Text(
                            last?.let { (if (it.mine) "Vos: " else "") + it.text } ?: "Sin mensajes",
                            style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (last != null) HudLabel(timeFmt.format(Date(last.time * 1000)))
                    if (unread > 0) { Spacer(Modifier.width(8.dp)); Badge(containerColor = SigFair) { Text("$unread") } }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            }
        }
        HudLabel("Mensaje directo: tocá un nodo en el mapa o en NODOS", Modifier.padding(vertical = 10.dp))
        HudLabel("Para responder un mensaje, mantenelo apretado", Modifier.padding(bottom = 10.dp))
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
    var replying by remember(conv) { mutableStateOf<ChatMsg?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    fun nameOf(m: ChatMsg): String = if (m.mine) "Vos" else nodes[m.from]?.let { n ->
        n.shortName.ifEmpty { null } ?: n.longName.ifEmpty { null }
    } ?: "!%08x".format(m.from)

    LaunchedEffect(msgs.size) {
        vm.markRead(conv)
        if (msgs.isNotEmpty()) list.animateScrollToItem(msgs.size - 1)
    }

    Column(Modifier.fillMaxSize().imePadding().padding(horizontal = 12.dp)) {
        HudHeader(convTitle(vm, conv), onBack = onBack)
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(msgs, key = { "${it.from}:${it.id}" }) { m ->
                val sender = if (m.mine) null else nodes[m.from]?.let { n ->
                    n.shortName.ifEmpty { null } ?: n.longName.ifEmpty { null }
                } ?: "!%08x".format(m.from)
                val quoted = m.replyTo?.let { r -> msgs.firstOrNull { it.id == r } }
                Bubble(
                    m, if (isChannel) sender else null, onRetry = { vm.retry(m) },
                    senderColor = if (m.mine) null else io.github.protohommer.meshpaper.ui.theme.memberColor(m.from),
                    onReply = { replying = m },
                    quote = m.replyTo?.let { quoted?.let { q -> nameOf(q) to q.text } ?: (null to "un mensaje que no está acá") },
                    onQuote = quoted?.let { q -> { scope.launch { list.animateScrollToItem(msgs.indexOf(q).coerceAtLeast(0)) } } },
                )
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        replying?.let { r ->
            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp)
                    .background(HudSurface, androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                    .padding(start = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.width(2.dp).height(30.dp).background(HudLine))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("Respondiendo a ${nameOf(r)}", style = MaterialTheme.typography.labelMedium, color = HudTextDim)
                    Text(r.text, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                TextButton(onClick = { replying = null }) { Text("✕") }
            }
        }
        val bytes = text.trim().toByteArray(Charsets.UTF_8).size
        // Mensajes rápidos del nodo: un toque los pone en el cuadro (otro toque los manda).
        val canned by vm.canned.collectAsState()
        LaunchedEffect(Unit) { if (vm.canned.value == null) vm.loadCanned() }
        canned?.takeIf { it.isNotEmpty() }?.let { quick ->
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (q in quick) {
                    Box(
                        Modifier.heightIn(min = 40.dp)
                            .background(HudSurface, androidx.compose.foundation.shape.RoundedCornerShape(20.dp))
                            .border(1.dp, io.github.protohommer.meshpaper.ui.theme.HudControl, androidx.compose.foundation.shape.RoundedCornerShape(20.dp))
                            .clickable(onClickLabel = "Escribir \"$q\"") { text = q; error = null }
                            .padding(horizontal = 16.dp, vertical = 9.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text(q, style = MaterialTheme.typography.bodyMedium, maxLines = 1) }
                }
            }
        }
        Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it; error = null },
                modifier = Modifier.weight(1f),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                placeholder = { Text(if (isChannel) "Mensaje al grupo" else "Mensaje") },
                supportingText = if (bytes > MAX_TEXT_BYTES * 3 / 4) {
                    { Text("$bytes/$MAX_TEXT_BYTES") }
                } else null,
                isError = bytes > MAX_TEXT_BYTES,
                maxLines = 4,
            )
            Spacer(Modifier.width(8.dp))
            val canSend = text.isNotBlank() && bytes <= MAX_TEXT_BYTES
            Box(
                Modifier.size(52.dp)
                    .background(if (canSend) HudText else HudLine, androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
                    .clickable(enabled = canSend, onClickLabel = "Enviar") {
                        val why = vm.sendText(conv, text, replying?.id)
                        if (why == null) { text = ""; replying = null } else error = why
                    },
                contentAlignment = Alignment.Center,
            ) {
                val ink = if (canSend) HudBg else HudTextDim
                androidx.compose.foundation.Canvas(Modifier.size(22.dp)) {
                    val u = size.width / 22f
                    val p = androidx.compose.ui.graphics.Path().apply {
                        moveTo(3 * u, 11 * u); lineTo(19 * u, 3 * u); lineTo(13 * u, 19 * u); lineTo(11 * u, 12 * u); close()
                    }
                    drawPath(p, ink, style = androidx.compose.ui.graphics.drawscope.Stroke(2 * u, join = androidx.compose.ui.graphics.StrokeJoin.Round))
                }
            }
        }
    }
}

private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun Bubble(
    m: ChatMsg, sender: String?, onRetry: () -> Unit,
    onReply: () -> Unit, quote: Pair<String?, String>?, onQuote: (() -> Unit)?,
    senderColor: Color? = null,
) {
    val me = io.github.protohommer.meshpaper.ui.theme.SigMe
    val bg = if (m.mine) me.copy(alpha = 0.07f) else MaterialTheme.colorScheme.surfaceVariant
    val fg = MaterialTheme.colorScheme.onSurface
    val edge = when (m.status) {
        MsgStatus.FAILED -> MaterialTheme.colorScheme.error
        else -> if (m.mine) me else senderColor?.copy(alpha = 0.55f) ?: HudLine
    }
    val shape = if (m.mine) androidx.compose.foundation.shape.RoundedCornerShape(14.dp, 4.dp, 14.dp, 14.dp)
    else androidx.compose.foundation.shape.RoundedCornerShape(4.dp, 14.dp, 14.dp, 14.dp)
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (m.mine) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top,
    ) {
        // En el chat del grupo, la inicial de quien escribe en su color: se distingue a cada uno de un vistazo.
        if (!m.mine && sender != null) {
            val c = senderColor ?: HudText
            Box(
                Modifier.padding(top = 2.dp, end = 8.dp).size(32.dp)
                    .background(c.copy(alpha = 0.16f), androidx.compose.foundation.shape.CircleShape)
                    .border(1.5.dp, c, androidx.compose.foundation.shape.CircleShape),
                contentAlignment = Alignment.Center,
            ) { Text(sender.take(2).uppercase(), style = MaterialTheme.typography.labelMedium, color = c, fontWeight = FontWeight.Bold) }
        }
        Column(
            Modifier.widthIn(max = 300.dp)
                .background(bg, shape)
                .border(1.dp, edge, shape)
                // Toque: reintentar si falló. Toque largo: responder.
                .combinedClickable(
                    onClick = { if (m.status == MsgStatus.FAILED) onRetry() },
                    onLongClick = onReply,
                )
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            sender?.let { Text(it.uppercase(), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = senderColor ?: fg) }
            quote?.let { (who, what) ->
                // Cita del mensaje al que responde; tocarla lleva hasta él.
                Row(
                    Modifier.padding(vertical = 3.dp)
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f))
                        .then(if (onQuote != null) Modifier.clickable(onClick = onQuote) else Modifier)
                        .padding(end = 6.dp),
                ) {
                    Box(Modifier.width(2.dp).height(32.dp).background(HudLine))
                    Spacer(Modifier.width(6.dp))
                    Column {
                        Text("↩ " + (who ?: "Respuesta"), style = MaterialTheme.typography.labelSmall, color = HudTextDim)
                        Text(what, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis, color = fg.copy(alpha = 0.75f))
                    }
                }
            }
            Text(m.text, color = fg, style = MaterialTheme.typography.bodyLarge)
            val status = when (m.status) {
                MsgStatus.RECEIVED -> ""
                MsgStatus.SENDING -> if (m.attempt > 1) " · reintentando…" else " · enviando…"
                MsgStatus.RELAYED -> if (m.to == -1) " · ✓ salió" else " · ✓ repetido"
                MsgStatus.DELIVERED -> " · ✓✓ entregado" + (m.hops?.let { " (${hopsText(it)})" } ?: "")
                MsgStatus.FAILED -> " · ✗ ${m.error ?: "falló"} · tocá para reintentar"
            }
            val hops = if (!m.mine) m.hops?.let { " · ${hopsText(it)}" }.orEmpty() else ""
            Text(
                timeFmt.format(Date(m.time * 1000)) + hops + status,
                style = MaterialTheme.typography.labelSmall,
                color = if (m.status == MsgStatus.FAILED) MaterialTheme.colorScheme.error else fg.copy(alpha = 0.7f),
            )
        }
    }
}
