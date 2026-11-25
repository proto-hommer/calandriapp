package io.github.protohommer.meshpaper.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.protohommer.meshpaper.Updater
import io.github.protohommer.meshpaper.ui.theme.HudCyan
import io.github.protohommer.meshpaper.ui.theme.HudTextDim
import io.github.protohommer.meshpaper.ui.theme.SigGood
import kotlinx.coroutines.launch

/** Cuadro de actualización: avisa, baja con progreso, verifica e instala. Se muestra solo cuando hace falta. */
@Composable
fun UpdateDialog() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val st by Updater.state.collectAsState()
    when (val s = st) {
        is Updater.State.Available -> AlertDialog(
            onDismissRequest = { Updater.snooze(ctx, s.r.version) },
            title = { Text("Versión nueva: ${s.r.version}") },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    HudLabel("Tenés la ${Updater.installedVersion(ctx)}")
                    Spacer(Modifier.padding(4.dp))
                    Text(s.r.notes.ifEmpty { "Las novedades están en la página del release, en GitHub." }, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { scope.launch { Updater.download(ctx, s.r) } }) { Text("ACTUALIZAR") } },
            dismissButton = { TextButton(onClick = { Updater.snooze(ctx, s.r.version) }) { Text("MÁS TARDE") } },
        )
        is Updater.State.Downloading -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Bajando la ${s.r.version}…") },
            text = {
                Column {
                    LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.padding(4.dp))
                    HudLabel("${(s.progress * 100).toInt()} % · después se verifica la firma")
                }
            },
            confirmButton = {},
        )
        is Updater.State.Ready -> {
            // Si falta el permiso de instalar: se vuelve a mirar al volver de Ajustes (ON_RESUME).
            var needPerm by remember { mutableStateOf(!Updater.canInstall(ctx)) }
            val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
            androidx.compose.runtime.DisposableEffect(owner) {
                val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
                    if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) needPerm = !Updater.canInstall(ctx)
                }
                owner.lifecycle.addObserver(obs)
                onDispose { owner.lifecycle.removeObserver(obs) }
            }
            AlertDialog(
                onDismissRequest = { Updater.dismiss() },
                title = { Text("Lista para instalar") },
                text = {
                    Column {
                        Text("Versión ${s.r.version} bajada y verificada: mismo certificado de firma y mismo SHA-256 que el publicado.",
                            style = MaterialTheme.typography.bodySmall, color = SigGood)
                        if (needPerm) {
                            Spacer(Modifier.padding(4.dp))
                            Text(
                                "Android pide permitir que CalandriApp instale apps (una sola vez). Tocá PERMITIR, " +
                                    "activá el permiso (\"Permitir\" o \"Confiar en esta fuente\", según el celular), " +
                                    "volvé y tocá INSTALAR.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Spacer(Modifier.padding(4.dp))
                        HudLabel("Android te va a pedir confirmar · no se pierde nada", maxLines = 2)
                        Spacer(Modifier.padding(2.dp))
                        Text(
                            "Si aparece Google Play Protect: \"Más detalles\" → \"Instalar sin analizar\" " +
                                "(o \"Analizar app\", que la manda a Google y tarda un poco).",
                            style = MaterialTheme.typography.bodySmall, color = HudTextDim,
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (Updater.canInstall(ctx)) { needPerm = false; Updater.install(ctx, s.file) }
                        else { needPerm = true; Updater.openInstallPermission(ctx) }
                    }) { Text(if (needPerm) "PERMITIR" else "INSTALAR") }
                },
                dismissButton = { TextButton(onClick = { Updater.dismiss() }) { Text("DESPUÉS") } },
            )
        }
        is Updater.State.Error -> AlertDialog(
            onDismissRequest = { Updater.dismiss() },
            title = { Text("Actualización") },
            text = { Text(s.message, style = MaterialTheme.typography.bodySmall) },
            confirmButton = { TextButton(onClick = { Updater.dismiss() }) { Text("CERRAR") } },
        )
        else -> Unit
    }
}

/** Ajustes → Actualizaciones: buscar al abrir (sí/no) y buscar ahora. */
@Composable
fun UpdatesPanel() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val st by Updater.state.collectAsState()
    var auto by remember { mutableStateOf(Updater.autoCheck(ctx)) }
    HudPanel(Modifier.fillMaxWidth(), title = "Actualizaciones") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Buscar al abrir la app", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Consulta en GitHub si hay una versión nueva. No manda datos tuyos ni del nodo.",
                    style = MaterialTheme.typography.bodySmall, color = HudTextDim,
                )
            }
            Spacer(Modifier.width(8.dp))
            Switch(checked = auto, onCheckedChange = { auto = it; Updater.setAutoCheck(ctx, it) })
        }
        Spacer(Modifier.padding(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            HudOutlinedButton(
                onClick = { scope.launch { Updater.check(ctx, manual = true) } },
                enabled = st !is Updater.State.Checking && st !is Updater.State.Downloading,
            ) { Text("BUSCAR AHORA") }
            Spacer(Modifier.width(12.dp))
            HudLabel(
                when (st) {
                    Updater.State.Checking -> "Consultando…"
                    Updater.State.UpToDate -> "Estás al día (${Updater.installedVersion(ctx)})"
                    else -> "Tenés la ${Updater.installedVersion(ctx)}"
                },
                color = if (st == Updater.State.UpToDate) SigGood else HudCyan,
            )
        }
    }
}
