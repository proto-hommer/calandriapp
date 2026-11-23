package io.github.protohommer.meshpaper.map

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.TileSourcePolicy
import org.osmdroid.util.MapTileIndex
import java.io.File

/**
 * Tiles del mapa. Todo vive en el almacenamiento privado de la app (sin permisos de storage):
 *
 *  - `maps/` es la "base path" de osmdroid. Los `.mbtiles` que se importan van ahí y el
 *    MapTileFileArchiveProvider de MapTileProviderBasic los encuentra solo.
 *  - `maps/tiles/` es la caché SQLite de osmdroid. Con un servidor propio cada tile que se mira
 *    queda guardado un año y sirve sin señal. Con OpenStreetMap se respeta la caché que pide su
 *    servidor: su política no permite el uso offline (para eso, .mbtiles o servidor propio).
 *
 * Orden en que se busca un tile: caché → archivos importados → aproximación desde otro zoom →
 * descarga (solo si "usar internet" está prendido).
 *
 * OpenStreetMap no permite descargas masivas (osmdroid lo marca con FLAG_NO_BULK), así que con
 * OSM solo se guarda lo que se va mirando. "Descargar zona" requiere un servidor propio.
 */
object MapTiles {
    private const val PREFS = "map"
    const val MAX_DOWNLOAD_TILES = 20_000

    fun baseDir(ctx: Context) = File(ctx.filesDir, "maps")

    /** Configura osmdroid. Llamar antes de crear cualquier MapView. */
    fun init(ctx: Context) {
        val conf = Configuration.getInstance()
        conf.load(ctx, ctx.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
        val base = baseDir(ctx).apply { mkdirs() }
        conf.osmdroidBasePath = base
        conf.osmdroidTileCache = File(base, "tiles").apply { mkdirs() }
        // OSM pide un User-Agent que identifique la app; uno genérico, sin datos del usuario.
        conf.userAgentValue = "CalandriApp-Android/1.0"
        conf.tileFileSystemCacheMaxBytes = 1024L * 1024 * 1024        // 1 GB de caché
        conf.tileFileSystemCacheTrimBytes = 900L * 1024 * 1024
        applyCachePolicy(ctx)
    }

    // ---------- Fuente de tiles ----------

    /** URL tipo `https://servidor/{z}/{x}/{y}.png`, o vacío = OpenStreetMap. */
    fun customUrl(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("tileUrl", "").orEmpty()

    /**
     * Política de caché según la fuente. OpenStreetMap: respetar los encabezados de su servidor
     * (operations.osmfoundation.org/policies/tiles). Servidor propio: guardar un año, para el campo.
     */
    fun applyCachePolicy(ctx: Context) {
        Configuration.getInstance().expirationOverrideDuration =
            if (isValidTemplate(customUrl(ctx))) 365L * 24 * 3600 * 1000 else null
    }

    fun setCustomUrl(ctx: Context, url: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("tileUrl", url.trim()).apply()
        applyCachePolicy(ctx)
    }

    fun isValidTemplate(url: String): Boolean {
        val u = url.trim()
        return (u.startsWith("https://") || u.startsWith("http://")) &&
            "{z}" in u && "{x}" in u && "{y}" in u
    }

    fun useInternet(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("online", true)

    fun setUseInternet(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("online", on).apply()
    }

    /** Estilo HUD oscuro (filtro de color sobre los tiles). */
    fun darkMap(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("dark", true)

    fun setDarkMap(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("dark", on).apply()
    }

    fun tileSource(ctx: Context): OnlineTileSourceBase {
        val url = customUrl(ctx)
        return if (isValidTemplate(url)) TemplateTileSource(url) else TileSourceFactory.MAPNIK
    }

    fun canBulkDownload(src: ITileSource): Boolean =
        src is OnlineTileSourceBase && src.tileSourcePolicy.acceptsBulkDownload()

    /** Proveedor nuevo: vuelve a buscar los archivos importados. */
    fun newProvider(ctx: Context): MapTileProviderBasic =
        MapTileProviderBasic(ctx.applicationContext, tileSource(ctx)).apply {
            setUseDataConnection(useInternet(ctx))
        }

    /**
     * Servidor de tiles propio con una plantilla {z}/{x}/{y}. Se usa un nombre por URL para que
     * la caché no mezcle tiles de servidores distintos.
     */
    class TemplateTileSource(private val template: String) : OnlineTileSourceBase(
        "custom-" + Integer.toHexString(template.hashCode()),
        0, 19, 256, ".png", arrayOf(template), "",
        TileSourcePolicy(2, 0),
    ) {
        override fun getTileURLString(pMapTileIndex: Long): String = template
            .replace("{z}", MapTileIndex.getZoom(pMapTileIndex).toString())
            .replace("{x}", MapTileIndex.getX(pMapTileIndex).toString())
            .replace("{y}", MapTileIndex.getY(pMapTileIndex).toString())
    }

    // ---------- Archivos importados ----------

    fun archives(ctx: Context): List<File> =
        baseDir(ctx).listFiles { f -> f.isFile && f.name.endsWith(".mbtiles", ignoreCase = true) }
            ?.sortedBy { it.name.lowercase() } ?: emptyList()

    /**
     * Copia un `.mbtiles` elegido con el selector de archivos. Devuelve null si salió bien,
     * o el motivo si no.
     */
    fun import(ctx: Context, uri: Uri): String? {
        val cr = ctx.contentResolver
        var name = cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: "mapa.mbtiles"
        name = name.replace(Regex("[^A-Za-z0-9._ -]"), "_")
        if (!name.endsWith(".mbtiles", ignoreCase = true)) {
            return "tiene que ser un archivo .mbtiles"
        }
        val dest = File(baseDir(ctx), name)
        val tmp = File(baseDir(ctx), "$name.part")
        try {
            cr.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { input.copyTo(it, 1 shl 16) }
            } ?: return "no pude abrir el archivo"
            val header = tmp.inputStream().use { s -> ByteArray(16).also { s.read(it) } }
            if (String(header, Charsets.US_ASCII) != "SQLite format 3\u0000") {
                tmp.delete()
                return "el archivo no es un MBTiles válido (no es SQLite)"
            }
            dest.delete()
            if (!tmp.renameTo(dest)) {
                tmp.delete()
                return "no pude guardar el archivo"
            }
        } catch (e: Exception) {
            tmp.delete()
            return e.message ?: "error al copiar"
        }
        return null
    }

    fun delete(f: File): Boolean = f.parentFile?.name == "maps" && f.delete()

    fun formatSize(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> "%.0f MB".format(bytes / (1L shl 20).toDouble())
        else -> "${bytes / 1024} KB"
    }
}
