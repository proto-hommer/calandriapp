package io.github.protohommer.meshpaper

import java.net.HttpURLConnection
import java.net.URL

/**
 * Plan B para consultar releases sin la API de GitHub. La API deja 60 consultas por hora por IP sin
 * cuenta, y con datos móviles muchos usuarios salen por la misma IP (CGNAT): responde 403 aunque
 * nosotros no hayamos consultado nada. Las páginas web de los releases no tienen ese límite.
 *
 * - El último release: github.com/<repo>/releases/latest redirige a .../releases/tag/<tag>.
 * - Los archivos y sus SHA-256: .../releases/expanded_assets/<tag> (el HTML de la lista de assets).
 * - El commit de un tag: la página del release enlaza a /commit/<sha>.
 */
object GitHubWeb {
    private fun open(url: String, follow: Boolean = true): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 30_000
        instanceFollowRedirects = follow
        setRequestProperty("User-Agent", "CalandriApp-Android")
    }

    /** ¿La API dijo que no por el límite de consultas? (403 o 429) */
    fun isRateLimit(e: Throwable): Boolean = e.message?.let { "403" in it || "429" in it } == true

    private fun text(url: String): String {
        val c = open(url)
        try {
            if (c.responseCode != 200) throw IllegalStateException("GitHub respondió ${c.responseCode}")
            return c.inputStream.bufferedReader().readText()
        } finally {
            c.disconnect()
        }
    }

    /** El tag del release marcado como Latest ("v1.9.0", "calandria-0.6.0"), o null. */
    fun latestTag(repo: String): String? {
        val c = open("https://github.com/$repo/releases/latest", follow = false)
        try {
            val loc = c.getHeaderField("Location") ?: return null
            return loc.substringAfter("/releases/tag/", "").takeIf { it.isNotEmpty() && '/' !in it }
        } finally {
            c.disconnect()
        }
    }

    data class Asset(val url: String, val sha256: String?)

    /** El primer archivo de [tag] que termina en [suffix], con su SHA-256 si GitHub lo muestra. */
    fun asset(repo: String, tag: String, suffix: String): Asset? {
        val html = text("https://github.com/$repo/releases/expanded_assets/$tag")
        val href = Regex("""href="(/$repo/releases/download/[^"]+${Regex.escape(suffix)})"""").find(html)?.groupValues?.get(1)
            ?: return null
        // El digest va en la misma fila del archivo, después del enlace.
        val after = html.substring(html.indexOf(href))
        val sha = Regex("""sha256:([0-9a-f]{64})""").find(after)?.groupValues?.get(1)
        return Asset("https://github.com$href", sha)
    }

    /** El commit (sha de 40) al que apunta [tag], según la página del release. */
    fun tagCommit(repo: String, tag: String): String? =
        Regex("""/$repo/commit/([0-9a-f]{40})""").find(text("https://github.com/$repo/releases/tag/$tag"))?.groupValues?.get(1)
}
