// Generado por assets/brand/tools/build.mjs desde assets/brand/marca.svg: no editar a mano.
package io.github.protohommer.meshpaper.ui.brand

/** Geometría de la marca (coordenadas de marca.svg) para animarla. */
internal object BrandGeometry {
    /** Caja de la marca completa: x, y, ancho, alto. */
    val box = floatArrayOf(24.8f, 43.8f, 146.4f, 127.4f)

    /** Nodos (x, y); el último es el ojo. */
    val points = floatArrayOf(168f, 62f, 138f, 47f, 124f, 53f, 98f, 74f, 72f, 106f, 28f, 156f, 36f, 168f, 84f, 124f, 120f, 120f, 146f, 98f, 152f, 70f, 108f, 96f, 128f, 92f, 136f, 66f)
    const val EYE = 13

    /** Contorno, como índices de [points]. */
    val outline = intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10)

    /** Conexiones internas, de a pares de índices. */
    val edges = intArrayOf(0, 13, 1, 13, 2, 13, 10, 13, 3, 13, 3, 11, 13, 12, 10, 12, 9, 12, 8, 12, 11, 12, 11, 4, 11, 7, 11, 8, 4, 7, 7, 5, 11, 13)

    const val STROKE = 1.8f
    const val NODE_R = 3.2f
    const val EYE_R = 6f
    const val EYE_RING = 2f
    const val FILL_ALPHA = 0.14f
}
