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

App **liviana para trabajo de campo**: sin cuentas, sin nube, sin Google Play Services, todo local.
Prioridades: comunicación, puntos en el mapa y rastreo de nodos, dentro de un **grupo cerrado**.
Los nodos ajenos solo sirven de repetidores. Los del grupo pueden ser de cualquier tipo (Mesh Paper,
custom o comerciales) y cada uno usa la app que quiera, esta o la oficial.

### Grupo cerrado (confirmado en la doc 2.8 y en `C:\mt/src/mesh/RadioInterface.cpp`)

- Región **ANZ** (Argentina figura como ANZ en la tabla region-by-country, 915–928 MHz).
- **Primario (índice 0) = canal privado del grupo**, con PSK AES-256 aleatoria. La posición y la
  telemetría periódicas salen por el primario, así que quedan cifradas.
- **Secundario = canal público** (LongFast, clave por defecto) para hablar con gente de afuera.
- **`lora.channel_num = 20` fijo.** El slot sale de `djb2(nombre del primario) % slots`. En ANZ con
  LongFast hay 52 slots, y "LongFast" da el slot 20 (919,875 MHz). Si no se fija, renombrar el primario
  cambia la frecuencia y los nodos públicos dejan de oírnos.
- Los nodos públicos con `rebroadcast_mode = ALL` (el valor por defecto) repiten los paquetes de otras
  redes con los mismos parámetros de modem aunque cambie el cifrado. Con `LOCAL_ONLY`/`KNOWN_ONLY`, no.
- Uplink y downlink MQTT apagados en el canal privado. Ojo: los encabezados (from, to, saltos) van sin cifrar.
- El grupo se comparte con la **URL/QR estándar de Meshtastic** (`ChannelSet` en `apponly.proto`, que
  incluye `lora_config`). Así es compatible con la app oficial.

### Etapas

1. Conectar por BLE, emparejar, leer la config y listar los nodos.
2. Grupo: crear el canal privado (con el slot fijado) y compartirlo o unirse por QR. La UI filtra a los
   miembros y muestra el resto como repetidores.
3. Mensajes de canal y DM, con ACK de entrega y reintento.
4. Mandar la posición del celular al nodo, periódica, por el primario.
5. Mapa **offline** con osmdroid: descargar una zona, ver los nodos del grupo con su rastro y los
   waypoints (crearlos y recibirlos).

## Reglas de trabajo

- Leer antes de escribir: no inventar APIs ni campos de protobuf, confirmarlos en el código o la doc.
- Compilar (`./gradlew assembleDebug`) al final de cada etapa y confirmar que pasa.
- Un commit por etapa. Nada de `push` sin confirmar.
- Probar en un teléfono real: BLE no anda en el emulador.
