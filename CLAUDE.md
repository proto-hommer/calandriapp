# Mesh Paper — app Android

App Android chica y propia para usar junto a un nodo **Mesh Paper 1.54**: Meshtastic en una
Waveshare ESP32-S3-ePaper-1.54 con un Wio-SX1262. Firmware y documentación del nodo:
https://github.com/proto-hommer/mesh-paper-154

No es un fork de la app oficial de Meshtastic. Es una app más simple que habla con el nodo
por Bluetooth LE usando la API pública de Meshtastic.

Respondé siempre en **español rioplatense**, claro y directo.

---

## Stack

- Kotlin, Jetpack Compose, minSdk 26 (BLE moderno y permisos razonables).
- El proyecto se crea con Android Studio ("Empty Activity", Compose), no a mano: así Gradle y el
  wrapper quedan en las versiones instaladas.
- Protobufs de Meshtastic: repo `meshtastic/protobufs`, como submódulo o copiados, con el plugin
  `com.google.protobuf` en modo **lite** (Android).

## Cómo se habla con el nodo (BLE)

Servicio y características de Meshtastic. **Confirmarlos contra la documentación oficial**
(meshtastic.org → Development → Device → Client API) antes de usarlos:

| Qué | UUID |
|---|---|
| Servicio | `6ba1b218-15a8-461f-9fa8-5dcae273eafd` |
| ToRadio (escribir) | `f75c76d2-129e-4dad-a1dd-7866124401e7` |
| FromRadio (leer) | `2c55e69e-4993-11ed-b878-0242ac120002` |
| FromNum (notify) | `ed9da18b-a800-4f66-a670-aa7547e34453` |

Flujo: conectar → pedir MTU alto → escribir `ToRadio{want_config_id}` → leer `FromRadio` en loop
hasta que venga vacío (llegan MyNodeInfo, NodeInfo, Config, Channels, `config_complete_id`) →
suscribirse a FromNum y, en cada notify, volver a leer FromRadio hasta vacío.

El nodo pide PIN de emparejamiento (por defecto `123456`, o el que muestre la pantalla).

## Lo que hace el nodo

Pantalla e-paper con InkHUD y encoder rotativo. Tiene lista de nodos con ficha, mensajes directos,
respuestas, teclado en pantalla, brújula de waypoints (norte arriba: **no tiene magnetómetro**),
radar de los 10 nodos más cercanos y pantalla de estado (SHTC3, batería, uso del canal). **No tiene
GPS todavía:** su posición la manda el celular. Eso importa para la app: si la app reemplaza a la
oficial, tiene que mandar la posición del teléfono al nodo.

## Alcance

**A definir en la primera sesión con el usuario.** Ideas sobre la mesa:
- Lista de nodos y mensajes (canal y DM).
- Mandar la posición del celular al nodo (hoy lo hace la app oficial).
- Ver en el celular el radar y los waypoints del nodo, sobre un mapa.

Empezar por lo mínimo que funcione de punta a punta (conectar + listar nodos) y crecer desde ahí.

## Reglas de trabajo

- Leer antes de escribir: no inventar APIs ni campos de protobuf, confirmarlos en el código o la doc.
- Compilar (`./gradlew assembleDebug`) al final de cada etapa y confirmar que pasa.
- Un commit por etapa. Nada de `push` sin confirmar.
- Probar en un teléfono real: BLE no anda en el emulador.
