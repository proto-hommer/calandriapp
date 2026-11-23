package io.github.protohommer.meshpaper

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.protohommer.meshpaper.ui.theme.HudCyan
import androidx.compose.ui.graphics.toArgb

/**
 * Notificaciones del sistema. Canales separados para que cada uno se pueda silenciar desde los
 * ajustes de Android: directos, grupo, canal público y el aviso fijo de "conectada".
 */
object Notifier {
    const val CH_DM = "mensajes_directos"
    const val CH_GROUP = "mensajes_grupo"
    const val CH_PUBLIC = "mensajes_publico"
    const val CH_LINK = "conexion"

    const val LINK_ID = 1
    const val EXTRA_CONV = "conv"

    fun createChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(CH_DM, "Mensajes directos", NotificationManager.IMPORTANCE_HIGH),
                NotificationChannel(CH_GROUP, "Mensajes del grupo", NotificationManager.IMPORTANCE_HIGH),
                NotificationChannel(CH_PUBLIC, "Canal público", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Mensajes de los canales que no son el grupo"
                },
                NotificationChannel(CH_LINK, "Conexión con el nodo", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Aviso fijo mientras la app sigue conectada en segundo plano"
                    setShowBadge(false)
                },
            )
        )
    }

    fun canPost(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Abre la app; con [conv], directo en esa conversación. */
    fun openApp(ctx: Context, conv: String? = null): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (conv != null) putExtra(EXTRA_CONV, conv)
        }
        return PendingIntent.getActivity(
            ctx, conv?.hashCode() ?: 0, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun convId(conv: String) = 1000 + (conv.hashCode() and 0x7fffffff) % 100_000

    /** Mensaje nuevo: una notificación por conversación, con los últimos mensajes apilados. */
    fun message(ctx: Context, conv: String, channel: String, title: String, lines: List<Pair<String, String>>) {
        if (!canPost(ctx) || lines.isEmpty()) return
        val style = NotificationCompat.InboxStyle().setBigContentTitle(title)
        lines.takeLast(6).forEach { (who, text) -> style.addLine("$who: $text") }
        val (lastWho, lastText) = lines.last()
        val n = NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_stat_mensaje)
            .setColor(HudCyan.toArgb())
            .setContentTitle(title)
            .setContentText("$lastWho: $lastText")
            .setStyle(style)
            .setNumber(lines.size)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(if (channel == CH_PUBLIC) NotificationCompat.PRIORITY_DEFAULT else NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openApp(ctx, conv))
            .setAutoCancel(true)
            .build()
        try { NotificationManagerCompat.from(ctx).notify(convId(conv), n) } catch (_: SecurityException) {}
    }

    fun cancel(ctx: Context, conv: String) = NotificationManagerCompat.from(ctx).cancel(convId(conv))

    /** Aviso fijo del servicio: a qué nodo está conectada y un botón para cortar. */
    fun link(ctx: Context, text: String): android.app.Notification {
        val stop = PendingIntent.getService(
            ctx, 1, Intent(ctx, MeshService::class.java).setAction(MeshService.ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(ctx, CH_LINK)
            .setSmallIcon(R.drawable.ic_stat_enlace)
            .setColor(HudCyan.toArgb())
            .setContentTitle("CalandriApp")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(openApp(ctx))
            .addAction(0, "Desconectar", stop)
            .build()
    }
}
