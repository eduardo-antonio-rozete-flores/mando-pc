# Contratos de interfaz Mando PC v1 (A2)

Estado: **congelado** para la parte A2.
Documento base: `docs/PROTOCOL.md` (protocolo v1).
Implementaciones de referencia: `daemon/` (Rust) y `android/contracts/` (Kotlin).
Arnés ejecutable: `tools/contract_v1.py` (`py tools/contract_v1.py --test`).

Este documento define las fronteras entre capas. Ninguna capa puede
importar tipos concretos de otra: solo estas interfaces y los tipos de
dominio de la sección 2.

## 1. Reglas de dependencia

```
Android                                   Fedora
-------                                   ------
UI / layout                               daemon
   |                                        |
   v                                        v
InputSource  --->  PlayerState  --->  GamepadBackend
                        ^
                        |
                    Transport  (telefono <-> PC)
```

- `Transport` solo mueve **tramas de 64 bytes**. No interpreta el
  protocolo, no conoce slots, jugadores ni UUID.
- `GamepadBackend` solo crea/actualiza/destruye slots. No conoce
  transporte, sesiones ni UUID.
- `InputSource` solo emite **eventos logicos**. No envía tramas ni
  conoce el transporte.
- El orquestador (C3) es el único que conecta `Transport` con
  `GamepadBackend`.
- El servicio Android (B3) es el único que conecta `InputSource` con
  `Transport`.

Consecuencia congelada:

| Cambio | No debe tocar |
|---|---|
| AOA -> tethering -> ADB -> HID | orquestador, backend, layout |
| uinput -> uhid | transporte, servicio Android, layout |
| Cambiar el layout tactil | transporte, backend, protocolo |

## 2. Tipos de dominio

Todos los valores reflejan exactamente los campos de `INPUT` en
`docs/PROTOCOL.md`; no se reescalan en la frontera.

### 2.1 Identificadores

| Tipo | Rango | Notas |
|---|---|---|
| `SlotId` | `1..=4` | slot de jugador, persistente |
| `SessionId` | `uint32` | lo asigna el host en HELLO_ACK |
| `Seq` | `uint32` | por sesion, con vuelta |
| `TimestampUs` | `uint64` | reloj monotonico del emisor |

### 2.2 `PlayerState`

```
timestamp_us : u64
seq          : u32
buttons      : u32    mascara de la seccion 10 de PROTOCOL.md
lx, ly       : i16    stick izquierdo, -32768..32767
rx, ry       : i16    stick derecho,   -32768..32767
lt, rt       : u16    gatillos, 0..65535
gyro         : [i16;3] 0.1 grados/s
accel        : [i16;3] mili-g
touch        : TouchState
battery      : u8     0..100, 255 = desconocido
link         : u8     0..100
rtt_ms       : u8     ultimo RTT medido
```

`TouchState`: `active: bool`, `id: u8`, `x: u16`, `y: u16`,
`pressure: u16`. `x` e `y` van normalizados `0..65535`.

`PlayerState::neutral()` devuelve sticks y gatillos en cero, botones
en cero, `battery = 255`, `link = 0`. Es el estado que se escribe en
un slot al desconectar.

### 2.3 `InputEvent`

Eventos logicos que produce el layout. Los IDs coinciden con la
mascara de botones del protocolo.

| Evento | Campos | Efecto en `PlayerState` |
|---|---|---|
| `Button` | `id: ButtonId`, `pressed: bool` | fija/limpia el bit |
| `Stick` | `id: StickId`, `x: i16`, `y: i16` | `lx/ly` o `rx/ry` |
| `Trigger` | `id: TriggerId`, `value: u16` | `lt` o `rt` |
| `Touch` | `TouchState` | `touch` |
| `Gyro` | `[i16;3]` | `gyro` |
| `Accel` | `[i16;3]` | `accel` |
| `Battery` | `u8` | `battery` |
| `Link` | `u8` | `link` |

`ButtonId`: `A, B, X, Y, LB, RB, Back, Start, Guide, L3, R3,
DpadUp, DpadDown, DpadLeft, DpadRight, Touchpad, Capture`.
`StickId`: `Left, Right`. `TriggerId`: `Left, Right`.

Decisiones congeladas:
- La **zona muerta** se aplica en `InputSource`/layout (Android).
  El daemon no vuelve a aplicar zona muerta.
- La conversion de `Y` (protocolo: arriba positivo; evdev: abajo
  positivo) se hace en `GamepadBackend`, no en el transporte.
- La mascara de botones viaja tal cual; el backend la traduce a
  `BTN_*` de uinput/uhid.

## 3. Contrato 1: `Transport`

Mueve tramas completas de 64 bytes. Implementaciones previstas: AOA
(B2/C1), tethering + UDP y ADB en el futuro.

### 3.1 Rust (daemon)

```rust
pub trait Transport {
    fn open(&mut self) -> Result<(), TransportError>;
    fn close(&mut self) -> Result<(), TransportError>;
    fn read(&mut self, out: &mut [u8; FRAME_SIZE]) -> Result<bool, TransportError>;
    fn write(&mut self, frame: &[u8; FRAME_SIZE]) -> Result<(), TransportError>;
    fn on_disconnect(&mut self) -> Option<DisconnectReason>;
}
```

### 3.2 Kotlin (Android)

```kotlin
interface Transport {
    fun open()
    fun close()
    fun read(out: ByteArray): Boolean
    fun write(frame: ByteArray)
    fun onDisconnect(): DisconnectReason?
}
```

### 3.3 Semantica

- `open()`: prepara el canal. Idempotente. Si falla, el objeto queda
  cerrado.
- `close()`: libera el canal. Idempotente. No lanza si ya estaba
  cerrado.
- `read(out)`: devuelve `true` si copio **exactamente 64 bytes** en
  `out`; `false` si no hay trama completa en el timeout interno.
  Nunca devuelve tramas parciales. Puede bloquear como maximo el
  periodo de muestreo configurado (1 ms a 1000 Hz).
- `write(frame)`: escribe exactamente una trama de 64 bytes. Puede
  bloquearse hasta completar la transferencia.
- `on_disconnect()`: devuelve `Some(reason)` **una sola vez** tras
  una desconexion y luego `None`. Es la unica via de notificacion de
  caida; `read` puede devolver `Err(Disconnected)`.
- `open/close/read/write/on_disconnect` pueden llamarse desde hilos
  distintos salvo `read`, que tiene un solo lector por instancia.
- `Transport` **no** valida magic, version, tipo, session ni seq.
  Eso es responsabilidad del orquestador.

### 3.4 Errores

| Error | Cuando |
|---|---|
| `NotOpen` | operacion sin `open()` |
| `Disconnected { reason }` | cable retirado, timeout o cierre del otro lado |
| `Io(String)` | fallo del sistema operativo o de libusb |

`DisconnectReason`: `Normal, AppClosed, CableRemoved, Timeout, Error`.

### 3.5 Aceptacion

- `MockTransport` genera INPUT sintetico a 1000 Hz sin depender de
  hardware.
- El orquestador compila y funciona igual con AOA, tethering o el
  mock (misma firma, distinta implementacion).

## 4. Contrato 2: `GamepadBackend`

Crea y actualiza gamepads virtuales. Implementaciones previstas:
uinput (C2) y uhid (futuro).

### 4.1 Rust (daemon)

```rust
pub trait GamepadBackend {
    fn create_slot(&mut self, slot: SlotId) -> Result<(), BackendError>;
    fn update(&mut self, slot: SlotId, state: &PlayerState) -> Result<(), BackendError>;
    fn reset(&mut self, slot: SlotId) -> Result<(), BackendError>;
    fn destroy(&mut self, slot: SlotId) -> Result<(), BackendError>;
    fn slots(&self) -> Vec<SlotId>;
}
```

### 4.2 Semantica

- `create_slot(slot)`: crea el gamepad si no existe. Idempotente.
  Los 4 slots se crean al arrancar el daemon.
- `update(slot, state)`: aplica el estado completo. Requiere que el
  slot exista.
- `reset(slot)`: escribe `PlayerState::neutral()` sin destruir el
  slot. Se llama al desconectar o por timeout de INPUT.
- `destroy(slot)`: destruye el gamepad. Solo se usa al apagar el
  daemon o en pruebas; **nunca** al desconectar un telefono.
- `slots()`: lista de slots vivos, ordenada.

### 4.3 Errores

| Error | Cuando |
|---|---|
| `SlotNotFound(SlotId)` | `update`/`reset`/`destroy` sobre slot inexistente |
| `AlreadyExists(SlotId)` | solo si una implementacion no idempotente lo detecta |
| `Io(String)` | fallo de uinput/uhid/evdev |

### 4.4 Aceptacion

- `MockGamepad` imprime y registra en vez de tocar `/dev/uinput`.
- Cambiar uinput por uhid no obliga a tocar `C1` ni `C3`.
- Tras desconectar el jugador 2, el slot 2 sigue existiendo y en
  estado neutro.

## 5. Contrato 3: `InputSource`

Produce eventos logicos desde la pantalla tactil y los sensores.

### 5.1 Rust (mocks/daemon, para pruebas)

```rust
pub trait InputSource {
    fn poll(&mut self, now_us: u64) -> Option<InputEvent>;
}
```

### 5.2 Kotlin (Android)

```kotlin
interface InputSource {
    fun poll(nowUs: Long): InputEvent?
}
```

### 5.3 Semantica

- `poll(now_us)`: devuelve el siguiente evento pendiente o `null`.
  No bloquea.
- El layout es responsable de:
  - convertir toques en `Button`/`Stick`/`Trigger`/`Touch`;
  - aplicar zona muerta y saturacion;
  - mantener el orden de los eventos.
- `InputSource` no conoce `Transport` ni `PlayerState` interno; el
  servicio Android aplica los eventos con `apply_event` y construye
  la trama INPUT.
- Sin asignaciones en el camino caliente: reutilizar buffers y
  evitar listas por evento.

### 5.4 Aceptacion

- `MockInputSource` produce eventos sin pantalla tactil.
- Un evento `Button(A, true)` produce `buttons & 0x1 != 0` en
  `PlayerState`, y la trama INPUT decodificada coincide.
- 10 dedos simultaneos no cambian el contrato: cada toque es un
  `TouchState` independiente que el layout resume antes de emitir.

## 6. Composicion

### 6.1 Daemon

```
loop:
  if let Some(reason) = transport.on_disconnect():
      orchestrator.reset(slot)
      orchestrator.end_session()
      continue

  if transport.read(&mut frame)?:
      handle_frame(frame)   # HELLO, INPUT, PING, ...
```

`handle_frame` es el unico punto que conoce `PROTOCOL.md`. Traduce
INPUT a `PlayerState` y llama `backend.update(slot, &state)`.

### 6.2 Android

```
loop:
  while let Some(event) = input_source.poll(now_us):
      state = apply_event(state, event)
  transport.write(build_input(state))
```

El envio ocurre a la tasa configurada (1000 Hz por defecto).

## 7. Mocks de referencia

| Mock | Lenguaje | Que hace |
|---|---|---|
| `MockTransport` | Rust / Kotlin / Python | entrega INPUT sintetico a 1000 Hz y registra escrituras |
| `MockGamepad` | Rust / Python | imprime `slot + PlayerState` en vez de usar uinput |
| `MockInputSource` | Rust / Kotlin / Python | guion de eventos sin pantalla tactil |

Los mocks son deterministas: reciben el reloj como parametro y no
duermen en las pruebas.

## 8. Pruebas de contrato

| ID | Prueba | Criterio |
|---|---|---|
| TC-1 | Sustitucion de transporte | AOA, tethering y mock producen el mismo flujo de `update` en el backend |
| TC-2 | Sustitucion de backend | uinput y uhid mock reciben la misma secuencia de estados |
| TC-3 | 1000 Hz | 1 000 000 us de reloj simulado producen 1000 tramas |
| TC-4 | Slots estables | desconectar el slot 2 lo deja neutro y vivo; reconectar vuelve al 2 |
| TC-5 | Evento -> trama | `Button(A, true)` sobrevive `apply_event -> INPUT -> decode` |
| TC-6 | Fronteras | `Transport` no expone slots/UUID y `GamepadBackend` no expone transporte |

Ejecucion en este repo (sin toolchain Rust/Kotlin instalada):

```
py tools/contract_v1.py --test
```

Las pruebas Rust (`daemon/tests/contracts.rs`) y Kotlin
(`android/contracts/src/test/...`) son las mismas TC-1..TC-6 y se
ejecutan cuando A3 configure CI con `cargo` y Gradle.

## 9. Congelamiento y versionado

- Version de estos contratos: **v1**.
- Un cambio incompatible sube a v2 y exige aviso a B1/B2/B3/C1/C2/C3/D1.
- Cambios compatibles: agregar metodos con implementacion por
  defecto en Rust y `default` en Kotlin, o agregar variantes de
  evento que los consumidores puedan ignorar.
- No se permite que una capa dependa de un tipo concreto de otra
  (por ejemplo, que C3 importe `rusb` o que B3 importe `uinput`).

## 10. Decisiones cerradas en A2

- Zona muerta: en el layout Android, no en el daemon.
- Inversion de `Y`: en `GamepadBackend`.
- Slots 1..4 creados al arrancar y persistentes.
- `session_id` lo asigna el host; el telefono guarda UUID y slot
  pedido.
- `rtt_ms` se llena en el telefono tras el primer PONG; antes vale 0.
- Tasa por defecto: 1000 Hz; 500 Hz aceptable si el telefono no
  sostiene 1000.
- Touch normalizado `0..65535`, independiente de la resolucion.
- El protocolo (A1) no se toca en A2.

## 11. Pendientes fuera de A2

- Implementacion real de AOA (B2/C1) y de uinput (C2).
- Politica de reconexion y timeouts finos (C3/B3).
- Formato del archivo de configuracion (A3/C3).
- Toolchain Rust/Kotlin y CI (A3).
