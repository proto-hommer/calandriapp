package io.github.protohommer.meshpaper.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.protohommer.meshpaper.map.Dem
import io.github.protohommer.meshpaper.map.MapTiles
import io.github.protohommer.meshpaper.mesh.PhoneLocation
import io.github.protohommer.meshpaper.mesh.Profile
import io.github.protohommer.meshpaper.ui.theme.HudCyan
import io.github.protohommer.meshpaper.ui.theme.HudLine
import io.github.protohommer.meshpaper.ui.theme.HudTextDim
import io.github.protohommer.meshpaper.ui.theme.SigFair
import io.github.protohommer.meshpaper.ui.theme.SigFixed
import io.github.protohommer.meshpaper.ui.theme.SigGood
import io.github.protohommer.meshpaper.ui.theme.SigWeak
import org.meshtastic.proto.ConfigProtos.Config.LoRaConfig.ModemPreset
import kotlin.math.abs
import kotlin.math.roundToInt

private sealed interface ProfileState {
    data object Loading : ProfileState
    data object NoData : ProfileState
    data class Ready(val r: Profile.Result) : ProfileState
}

/**
 * Perfil de terreno entre [a] (este nodo) y [b]: el corte del relieve, la línea de vista, la
 * zona de Fresnel y si el enlace pasa. [aGuess]/[bGuess]: true si la antena es la supuesta.
 */
@Composable
fun ProfileDialog(
    aName: String, a: Profile.End, aGuess: Boolean,
    bName: String, b: Profile.End, bGuess: Boolean,
    freqMHz: Double, preset: ModemPreset,
    approxM: Double? = null,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    var state by remember { mutableStateOf<ProfileState>(ProfileState.Loading) }
    LaunchedEffect(a, b, freqMHz, preset) {
        state = ProfileState.Loading
        val pts = Profile.samplePoints(a, b)
        val ground = Dem.elevations(ctx, pts, network = MapTiles.useInternet(ctx))
        state = if (ground == null) ProfileState.NoData
        else ProfileState.Ready(Profile.compute(a, b, ground, freqMHz, preset))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("PERFIL $aName → $bName") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                when (val s = state) {
                    ProfileState.Loading -> Row {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.padding(6.dp))
                        Text("Leyendo el relieve…", style = MaterialTheme.typography.bodySmall)
                    }
                    ProfileState.NoData -> Text(
                        "Falta el relieve de esta zona. Prendé \"Usar internet\" (en CAPA) para que se baje " +
                            "solo, o bajalo antes de salir desde CAPA → Relieve.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    is ProfileState.Ready -> {
                        ProfileBody(s.r, aName, bName, a, b, aGuess, bGuess, freqMHz)
                        if (approxM != null && approxM > 100) Text(
                            "Ojo: una de las posiciones llega redondeada (±${PhoneLocation.formatDistance(approxM.toFloat())}), " +
                                "así que el perfil puede no ser el del lugar exacto.",
                            style = MaterialTheme.typography.bodySmall, color = SigFair,
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("CERRAR") } },
    )
}

@Composable
private fun ProfileBody(
    r: Profile.Result, aName: String, bName: String,
    a: Profile.End, b: Profile.End, aGuess: Boolean, bGuess: Boolean, freqMHz: Double,
) {
    val (color, title) = when (r.verdict) {
        Profile.Verdict.PASA -> SigGood to "PASA"
        Profile.Verdict.LIMITE -> SigFair to "AL LÍMITE"
        Profile.Verdict.NO_PASA -> SigWeak to "NO PASA"
    }
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        HudStat("Enlace", title, color = color)
        HudStat("Distancia", PhoneLocation.formatDistance(r.distanceM.toFloat()))
        r.marginDb?.let { HudStat("Margen", "%+d dB".format(it.roundToInt()), color = color) }
    }
    Spacer(Modifier.height(8.dp))
    ProfileChart(r, color, Modifier.fillMaxWidth().height(170.dp))
    Row(Modifier.fillMaxWidth()) {
        HudLabel(aName, Modifier.weight(1f))
        HudLabel(bName)
    }
    Spacer(Modifier.height(6.dp))

    val w = r.worst
    val obstacle = r.terrain[w] - r.los[w]
    val at = PhoneLocation.formatDistance(r.xs[w].toFloat())
    Text(
        when {
            r.worstRatio >= 0.6 -> "Línea de vista despejada: el terreno deja libre la zona de Fresnel."
            r.worstRatio >= 0 -> "Hay línea de vista, pero a $at el terreno se mete en la zona de Fresnel " +
                "(despeja ${(r.worstRatio * 100).roundToInt()} %)."
            else -> "A $at el terreno tapa la línea de vista por ${obstacle.roundToInt()} m."
        },
        style = MaterialTheme.typography.bodySmall,
    )
    if (r.diffractionDb >= 1) {
        Text(
            "Pérdida extra por el relieve ≈ ${r.diffractionDb.roundToInt()} dB (difracción).",
            style = MaterialTheme.typography.bodySmall,
        )
    }
    Spacer(Modifier.height(6.dp))
    fun endText(name: String, ground: Double, e: Profile.End, guess: Boolean) =
        "$name: terreno ${ground.roundToInt()} m, antena a ${fmt(e.antenna.heightM)} m" +
            if (guess) " (supuesta, de mano)" else ""
    val small = MaterialTheme.typography.bodySmall
    Text(endText(aName, r.ground.first(), a, aGuess), style = small, color = HudTextDim)
    Text(endText(bName, r.ground.last(), b, bGuess), style = small, color = HudTextDim)
    Text("Frecuencia %.3f MHz".format(freqMHz).replace('.', ','), style = small, color = HudTextDim)
    Spacer(Modifier.height(6.dp))
    Text(
        "Estimado con el relieve del SRTM (≈30 m): no incluye edificios ni árboles, y un margen de " +
            "10 dB o más es lo que da un enlace estable." +
            if (aGuess || bGuess) " Con los datos de antena (ANTENA en la ficha) el cálculo mejora." else "",
        style = MaterialTheme.typography.bodySmall, color = HudTextDim,
    )
    Spacer(Modifier.height(4.dp))
    Text(Dem.ATTRIBUTION, style = MaterialTheme.typography.labelSmall, color = HudTextDim)
}

private fun fmt(v: Double) = if (abs(v - v.roundToInt()) < 0.05) "${v.roundToInt()}" else "%.1f".format(v).replace('.', ',')

/** Terreno (con la curvatura de la Tierra), línea de vista y la zona de Fresnel. */
@Composable
private fun ProfileChart(r: Profile.Result, losColor: Color, modifier: Modifier) {
    Canvas(modifier) {
        val n = r.xs.size - 1
        var lo = Double.MAX_VALUE; var hi = -Double.MAX_VALUE
        for (i in 0..n) {
            lo = minOf(lo, r.terrain[i], r.los[i] - r.fresnel[i])
            hi = maxOf(hi, r.terrain[i], r.los[i] + r.fresnel[i])
        }
        val pad = (hi - lo) * 0.08 + 5
        lo -= pad; hi += pad
        val dist = r.distanceM.coerceAtLeast(1.0)
        fun x(i: Int) = (r.xs[i] / dist * size.width).toFloat()
        fun y(v: Double) = (size.height * (1 - (v - lo) / (hi - lo))).toFloat()

        // Grilla horizontal suave.
        for (k in 1..3) {
            val yy = size.height * k / 4
            drawLine(HudLine, Offset(0f, yy), Offset(size.width, yy), strokeWidth = 1f)
        }
        // Terreno relleno.
        val t = Path().apply {
            moveTo(0f, size.height)
            for (i in 0..n) lineTo(x(i), y(r.terrain[i]))
            lineTo(size.width, size.height)
            close()
        }
        drawPath(t, Color(0xFF3B4A3A))
        val edge = Path().apply { for (i in 0..n) if (i == 0) moveTo(x(i), y(r.terrain[i])) else lineTo(x(i), y(r.terrain[i])) }
        drawPath(edge, Color(0xFF8DA77F), style = Stroke(width = 2f))
        // Zona de Fresnel: borde de abajo y de arriba, punteados.
        val fresnelDash = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))
        for (sign in listOf(-1.0, 1.0)) {
            val p = Path()
            for (i in 0..n) {
                val yy = y(r.los[i] + sign * r.fresnel[i])
                if (i == 0) p.moveTo(x(i), yy) else p.lineTo(x(i), yy)
            }
            drawPath(p, SigFixed.copy(alpha = 0.7f), style = Stroke(width = 2f, pathEffect = fresnelDash))
        }
        // Mástiles y línea de vista.
        drawLine(HudCyan, Offset(x(0), y(r.terrain[0])), Offset(x(0), y(r.los[0])), strokeWidth = 4f)
        drawLine(HudCyan, Offset(x(n), y(r.terrain[n])), Offset(x(n), y(r.los[n])), strokeWidth = 4f)
        drawLine(losColor, Offset(x(0), y(r.los[0])), Offset(x(n), y(r.los[n])), strokeWidth = 3f)
        // Peor punto.
        if (r.worstRatio < 0.6) drawCircle(SigWeak, radius = 7f, center = Offset(x(r.worst), y(r.terrain[r.worst])))
    }
}
