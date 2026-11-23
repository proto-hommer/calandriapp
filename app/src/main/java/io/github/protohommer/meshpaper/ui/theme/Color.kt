package io.github.protohommer.meshpaper.ui.theme

import androidx.compose.ui.graphics.Color

// Paleta HUD: fondo casi negro azulado, líneas finas y acento cian.
val HudBg = Color(0xFF05090D)
val HudSurface = Color(0xFF0B1219)
val HudSurfaceHi = Color(0xFF111B24)
val HudLine = Color(0xFF1F3342)
val HudCyan = Color(0xFF35E0F5)
val HudCyanDim = Color(0xFF0E3A44)
val HudText = Color(0xFFD8E6EE)
val HudTextDim = Color(0xFF8EA4B3)

// Estado de los nodos en el mapa y en la lista.
val SigGood = Color(0xFF34E37A)   // grupo, buena recepción
val SigFair = Color(0xFFFFC83D)   // grupo, recepción media
val SigWeak = Color(0xFFFF4D5E)   // grupo, recepción mala o perdido
val SigRelay = Color(0xFFB26BFF)  // ajeno que repite (aporta cobertura)
val SigMute = Color(0xFF6B7785)   // ajeno que no repite
val SigFixed = Color(0xFF2F7DF6)  // del grupo, instalación fija (router, base): azul Francia, aclarado para fondo oscuro
val SigMe = HudCyan               // este teléfono / nodo propio
