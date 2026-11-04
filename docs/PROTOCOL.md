# Protocolo Mando PC v1

Estado: congelado para la parte A1.
Transporte inicial: AOA (Android Open Accessory) sobre USB.
Validador: `tools/protocol_v1.py` (`py tools/protocol_v1.py --selftest`).

## 1. Convenciones

- Todos los enteros son little-endian.
- Cada mensaje ocupa exactamente **64 bytes**.
- Los bytes de relleno y los campos reservados van en `0x00` y se ignoran.
- Las cadenas son UTF-8 sin terminador NUL; la longitud va en un campo aparte.
- Los timestamps están en microsegundos (`uint64`) y usan un reloj
  monotónico del emisor. No se comparan entre dispositivos.
- No hay retransmisión. El estado más reciente gana.
- El canal es por dispositivo USB: cada teléfono tiene su propio
  flujo. El `session_id` y el UUID identifican la sesión y el jugador.

## 2. Cabecera (16 bytes)

| Offset | Tamaño | Campo | Tipo | Valor |
|---:|---:|---|---|---|
| 0 | 4 | magic | bytes | `4D 4E 44 4F` ("MNDO") |
| 4 | 1 | version | uint8 | `1` |
| 5 | 1 | type | uint8 | ver tabla de tipos |
| 6 | 2 | flags | uint16 | `0` en v1; reservado |
| 8 | 4 | session_id | uint32 | `0` en HELLO; lo asigna el host |
| 12 | 4 | seq | uint32 | secuencia por sesión |

Capacidad de carga útil: `64 - 16 = 48` bytes.

## 3. Tipos de mensaje

| ID | Nombre | Dirección | Uso |
|---:|---|---|---|
| 1 | HELLO | teléfono → PC | presentación, UUID, slot pedido |
| 2 | HELLO_ACK | PC → teléfono | slot asignado, sesión, versión |
| 3 | INPUT | teléfono → PC | estado del mando |
| 4 | PING | teléfono → PC | medición de latencia |
| 5 | PONG | PC → teléfono | respuesta con timestamps |
| 6 | RUMBLE | PC → teléfono | vibración |
| 7 | BYE | ambos | cierre ordenado |
| 8 | ERROR | ambos | error de protocolo |

## 4. HELLO (tipo 1)

Carga útil de 48 bytes.

| Offset | Tamaño | Campo | Tipo | Notas |
|---:|---:|---|---|---|
| 0 | 8 | timestamp_us | uint64 | reloj del teléfono |
| 8 | 16 | uuid | bytes | identificador persistente |
| 24 | 1 | requested_slot | uint8 | `0` = automático, `1..4` = pedido |
| 25 | 2 | capabilities | uint16 | bits de capacidad |
| 27 | 1 | proto_min | uint8 | mínimo soportado |
| 28 | 1 | proto_max | uint8 | máximo soportado |
| 29 | 1 | model_len | uint8 | longitud real de `model` |
| 30 | 18 | model | bytes | UTF-8, máx. 18 bytes |

Capacidades (`capabilities`):

| Bit | Significado |
|---:|---|
| 0 | giroscopio disponible |
| 1 | touch disponible |
| 2 | vibración disponible |
| 3 | batería disponible |

Reglas:
- El teléfono envía HELLO con `session_id = 0` y `seq = 0`.
- El UUID se genera una vez y se guarda en el teléfono.
- Si `requested_slot` es `0`, el host elige el primer slot libre.
- El host responde siempre con HELLO_ACK.

## 5. HELLO_ACK (tipo 2)

Carga útil de 48 bytes.

| Offset | Tamaño | Campo | Tipo | Notas |
|---:|---:|---|---|---|
| 0 | 8 | timestamp_us | uint64 | reloj del host |
| 8 | 1 | assigned_slot | uint8 | `1..4` |
| 9 | 1 | status | uint8 | ver tabla |
| 10 | 1 | host_proto | uint8 | versión elegida |
| 11 | 1 | max_players | uint8 | normalmente `4` |
| 12 | 1 | name_len | uint8 | longitud real de `name` |
| 13 | 35 | name | bytes | nombre del host, UTF-8 |

Estados (`status`):

| Valor | Significado |
|---:|---|
| 0 | OK |
| 1 | slot pedido ocupado |
| 2 | versiones incompatibles |
| 3 | host lleno |
| 4 | petición inválida |

Reglas:
- El host asigna `session_id` en la cabecera del ACK.
- Si `status = 1` y el teléfono pidió un slot concreto, reintenta
  con `requested_slot = 0` tras 250 ms.
- Si `status = 2`, el teléfono cierra y muestra error.

## 6. INPUT (tipo 3)

Carga útil de 48 bytes. Se envía a la tasa configurada (por defecto
1000 Hz; 500 Hz es aceptable).

| Offset | Tamaño | Campo | Tipo | Unidad / rango |
|---:|---:|---|---|---|
| 0 | 8 | timestamp_us | uint64 | µs, reloj del teléfono |
| 8 | 4 | buttons | uint32 | máscara de botones |
| 12 | 2 | lx | int16 | stick izq. X, -32768..32767 |
| 14 | 2 | ly | int16 | stick izq. Y, -32768..32767 |
| 16 | 2 | rx | int16 | stick der. X, -32768..32767 |
| 18 | 2 | ry | int16 | stick der. Y, -32768..32767 |
| 20 | 2 | lt | uint16 | gatillo izq., 0..65535 |
| 22 | 2 | rt | uint16 | gatillo der., 0..65535 |
| 24 | 2 | gx | int16 | giro X, 0.1 °/s |
| 26 | 2 | gy | int16 | giro Y, 0.1 °/s |
| 28 | 2 | gz | int16 | giro Z, 0.1 °/s |
| 30 | 2 | ax | int16 | aceleración X, mg |
| 32 | 2 | ay | int16 | aceleración Y, mg |
| 34 | 2 | az | int16 | aceleración Z, mg |
| 36 | 1 | touch_active | uint8 | `0` o `1` |
| 37 | 1 | touch_id | uint8 | dedo |
| 38 | 2 | touch_x | uint16 | normalizado 0..65535 |
| 40 | 2 | touch_y | uint16 | normalizado 0..65535 |
| 42 | 2 | touch_pressure | uint16 | 0..65535 |
| 44 | 1 | battery | uint8 | 0..100, `255` = desconocido |
| 45 | 1 | link | uint8 | calidad de enlace 0..100 |
| 46 | 1 | rtt_ms | uint8 | último RTT medido, ms |
| 47 | 1 | pad | uint8 | `0x00` |

Convención de ejes:
- X crece hacia la derecha.
- Y crece hacia arriba en el protocolo. El daemon lo invierte al
  escribir en evdev/uinput, que usa Y hacia abajo.
- Centro de sticks: `0`. Zona muerta se aplica en el daemon o en la
  app, pero solo en uno de los dos (se decide en A2).

Unidades físicas:
- Giroscopio: `valor * 0.1` °/s. Rango ±3276.7 °/s.
- Acelerómetro: `valor` en mili-g. Rango ±32.767 g.
- Touch: coordenadas normalizadas, independientes de la resolución.

## 7. PING (tipo 4) y PONG (tipo 5)

PING, carga útil:

| Offset | Tamaño | Campo | Tipo |
|---:|---:|---|---|
| 0 | 8 | client_ts_us | uint64 |

PONG, carga útil:

| Offset | Tamaño | Campo | Tipo |
|---:|---:|---|---|
| 0 | 8 | client_ts_us | uint64 (eco) |
| 8 | 8 | host_ts_us | uint64 (reloj del host) |

Reglas:
- El teléfono envía PING cada 1000 ms.
- El host responde PONG copiando `client_ts_us`.
- El teléfono calcula `RTT = now_us - client_ts_us` y publica
  `RTT / 2` como latencia de ida y `rtt_ms` en INPUT.
- El host puede mostrar `rtt_ms` por jugador sin sincronizar relojes.

## 8. RUMBLE (tipo 6)

Carga útil:

| Offset | Tamaño | Campo | Tipo | Rango |
|---:|---:|---|---|---|
| 0 | 2 | left | uint16 | 0..65535 |
| 2 | 2 | right | uint16 | 0..65535 |
| 4 | 2 | duration_ms | uint16 | 0..65535 |
| 6 | 1 | flags | uint8 | reservado |

Reglas:
- El host lo envía solo si el teléfono anunció la capacidad.
- `duration_ms = 0` significa "hasta el siguiente RUMBLE".

## 9. BYE (tipo 7) y ERROR (tipo 8)

BYE, carga útil:

| Offset | Tamaño | Campo | Tipo |
|---:|---:|---|---|
| 0 | 1 | reason | uint8 |

Razones: `0` normal, `1` app cerrada, `2` cable retirado,
`3` timeout, `4` error.

ERROR, carga útil:

| Offset | Tamaño | Campo | Tipo |
|---:|---:|---|---|
| 0 | 2 | code | uint16 |
| 2 | 1 | msg_len | uint8 |
| 3 | 45 | message | bytes |

Códigos: `1` magic inválido, `2` versión no soportada,
`3` longitud inválida, `4` tipo desconocido, `5` slot ocupado,
`6` se esperaba HELLO, `7` límite de tasa, `8` error interno.

## 10. Máscara de botones

Diseñada para el layout Xbox.

| Bit | Botón |
|---:|---|
| 0 | A |
| 1 | B |
| 2 | X |
| 3 | Y |
| 4 | LB |
| 5 | RB |
| 6 | Back / View |
| 7 | Start / Menu |
| 8 | Guide |
| 9 | L3 (clic stick izq.) |
| 10 | R3 (clic stick der.) |
| 11 | D-pad arriba |
| 12 | D-pad abajo |
| 13 | D-pad izquierda |
| 14 | D-pad derecha |
| 15 | clic del touchpad |
| 16 | Capture |
| 17..31 | reservado |

## 11. Sesión, secuencia y tiempos

- `session_id` lo asigna el host en HELLO_ACK y es único por
  conexión. El teléfono lo usa en todos los mensajes siguientes.
- `seq` incrementa en cada INPUT y da la vuelta en `uint32`.
- El host descarta un INPUT si `(int32)(seq - last_seq) <= 0`, lo
  que maneja correctamente el desbordamiento.
- Timeouts:
  - HELLO: 2000 ms para recibir el ACK.
  - INPUT: 500 ms sin paquetes → el slot pasa a estado neutro
    (ejes al centro, botones sueltos), pero el gamepad virtual
    sigue existiendo.
  - PING: cada 1000 ms.
- Al reconectar, se crea una sesión nueva con `session_id` nuevo.

## 12. Multijugador

- Slots válidos: `1..4`.
- El host mantiene un mapa `UUID → slot` y lo persiste en su
  configuración.
- Política por defecto:
  1. Si el UUID ya tiene slot y está libre, se reutiliza.
  2. Si `requested_slot` está libre, se asigna.
  3. Si no, se asigna el primer slot libre.
  4. Si no hay slots, `status = 3`.
- Los 4 gamepads virtuales se crean al arrancar el daemon y no se
  destruyen al desconectar un teléfono. Así el orden de jugadores
  no cambia.
- Cada teléfono tiene su propio flujo y su propia latencia; no hay
  sincronización entre jugadores.

## 13. Mapeo a AOA

- VID de Android: `0x18D1`.
- PIDs de accesorio: `0x2D00` (accesorio) y `0x2D01`
  (accesorio + ADB).
- El PC (accesorio/host USB) inicia el handshake con peticiones de
  control: `51` GET_PROTOCOL, `52` SEND_STRING, `53`
  START_ACCESSORY.
- Tras START_ACCESSORY el teléfono reenumera como accesorio.
- El teléfono recibe `ACTION_USB_ACCESSORY_ATTACHED`, abre el
  accesorio y usa los endpoints bulk.
- Endpoints: bulk IN (teléfono → PC) y bulk OUT (PC → teléfono).
  La dirección exacta se lee del descriptor de interfaz.
- Tamaño máximo de paquete: 64 bytes en full-speed. Enviar
  exactamente 64 bytes por transferencia.
- El PC usa libusb (o equivalente) y abre un handle por teléfono.
- No hay retransmisión ni ACK por paquete: el estado más reciente
  gana.
- Para multijugador, el PC repite el handshake para cada teléfono.

## 14. Versionado

- `header.version` es la versión del formato de trama. En v1 es `1`.
- HELLO lleva `proto_min` y `proto_max`.
- El host elige la versión común más alta y la devuelve en
  `host_proto`.
- Si no hay versión común: ERROR `2` y cierre.
- Los cambios compatibles solo usan bits reservados o campos
  nuevos que el receptor pueda ignorar.
- Cualquier cambio incompatible sube `header.version`.

## 15. Vectores de prueba

Generados con `py tools/protocol_v1.py --selftest`.

### HELLO

```
4d 4e 44 4f 01 01 00 00 00 00 00 00 00 00 00 00
e8 03 00 00 00 00 00 00 00 01 02 03 04 05 06 07
08 09 0a 0b 0c 0d 0e 0f 00 0f 00 01 01 07 45 64
67 65 20 33 30 00 00 00 00 00 00 00 00 00 00 00
```

Decodificado: `timestamp_us=1000`, UUID
`000102030405060708090a0b0c0d0e0f`, `requested_slot=0`,
`capabilities=15`, `proto=1..1`, `model="Edge 30"`.

### HELLO_ACK

```
4d 4e 44 4f 01 02 00 00 01 00 00 00 00 00 00 00
d0 07 00 00 00 00 00 00 01 00 01 04 09 66 65 64
6f 72 61 2d 70 63 00 00 00 00 00 00 00 00 00 00
00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
```

Decodificado: `session_id=1`, `timestamp_us=2000`,
`assigned_slot=1`, `status=0`, `host_proto=1`, `max_players=4`,
`name="fedora-pc"`.

### INPUT (botón A presionado)

```
4d 4e 44 4f 01 03 00 00 01 00 00 00 0a 00 00 00
e8 03 00 00 00 00 00 00 01 00 00 00 00 00 00 00
00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
00 00 00 00 00 00 00 00 00 00 00 00 64 64 00 00
```

Decodificado: `session_id=1`, `seq=10`, `timestamp_us=1000`,
`buttons=1` (A), sticks y gatillos en 0, `battery=100`,
`link=100`, `rtt_ms=0`.

### PING

```
4d 4e 44 4f 01 04 00 00 01 00 00 00 00 00 00 00
88 13 00 00 00 00 00 00 00 00 00 00 00 00 00 00
00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
```

### PONG

```
4d 4e 44 4f 01 05 00 00 01 00 00 00 00 00 00 00
88 13 00 00 00 00 00 00 58 1b 00 00 00 00 00 00
00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
```

## 16. Validación

```
py tools/protocol_v1.py --selftest
```

El validador construye y parsea HELLO, HELLO_ACK, INPUT, PING y
PONG, e imprime los vectores. También rechaza magic inválido.

## 17. Pendientes de A1

- Confirmar los endpoints reales del Motorola Edge 30 (pueden
  variar; se leen del descriptor).
- Decidir si `rtt_ms` se llena siempre o solo tras el primer PING.
- Decidir la tasa por defecto: 1000 Hz o 500 Hz.
- Confirmar que el layout usa `touch_x`/`touch_y` normalizados o
  píxeles (v1 usa normalizado).
- Definir si el giroscopio se envía siempre o solo si el juego lo
  usa (v1: se envía siempre que esté disponible; si no, ceros).
- Definir el formato exacto del archivo de configuración del
  daemon (se hace en A2/A3).
