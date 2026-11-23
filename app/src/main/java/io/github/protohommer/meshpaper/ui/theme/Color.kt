package io.github.protohommer.meshpaper.ui.theme

import androidx.compose.ui.graphics.Color

// Paleta HUD neón: fondo casi negro azulado, líneas finas teñidas de cian y acentos saturados
// (se ven como luz sobre el fondo oscuro; los paneles les suman un halo).
val HudBg = Color(0xFF05090D)
val HudSurface = Color(0xFF0B1219)
val HudSurfaceHi = Color(0xFF111B24)
val HudLine = Color(0xFF1C4255)
val HudCyan = Color(0xFF1EF4FF)
val HudCyanDim = Color(0xFF0B3D48)
val HudText = Color(0xFFD8E6EE)
val HudTextDim = Color(0xFF8EA4B3)

// Marca CalandriApp (assets/brand): azul marino de fondo, cobre de la red, verde del ojo, texto.
val BrandNavy = Color(0xFF14223A)
val BrandCopper = Color(0xFFC98245)
val BrandGreen = Color(0xFF268647)
val BrandText = Color(0xFFF3F5F6)

// La marca en versión neón (intro): el cobre y el verde, encendidos.
val NeonCopper = Color(0xFFFF9A3C)
val NeonGreen = Color(0xFF2BFF88)

// Estado de los nodos en el mapa y en la lista.
val SigGood = Color(0xFF2BFF88)   // directo o 1 salto
val SigFair = Color(0xFFFFE135)   // 2 a 3 saltos
val SigPoor = Color(0xFFFF8A1F)   // 4 a 5 saltos
val SigWeak = Color(0xFFFF2E63)   // 6 o más saltos, o perdido
val SigRelay = Color(0xFFC45CFF)  // ajeno que repite (aporta cobertura): el nombre va en violeta
val SigMute = Color(0xFF6B7785)   // ajeno que no repite
val SigFixed = Color(0xFF2E8BFF)  // del grupo, instalación fija (router, base): azul eléctrico
val SigMe = HudCyan               // este teléfono / nodo propio
