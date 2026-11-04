# Transporte AOA (B2)

Implementacion Android del contrato `Transport` de A2. Mueve tramas
opacas de 64 bytes; no valida magic, version, tipo, sesion ni seq.

## Piezas

- `AoaTransport`: implementa `Transport` sobre un `UsbAccessory`.
  Registra el receptor de conexion/desconexion, pide permiso si hace
  falta y reabre solo cuando el cable vuelve.
- `FrameAssembler`: convierte lecturas USB parciales en tramas exactas
  de 64 bytes.
- `FrameRing`: cola circular sin asignaciones por operacion. Si se
  llena, descarta lo mas viejo (el protocolo no retransmite).
- `AoaIdentity`: cadenas que el host debe enviar en el handshake.

## Uso desde B3

```kotlin
val transport = AoaTransport(context)
transport.start()            // escucha el cable y abre si ya existe

// hilo de envio a 1000 Hz
transport.write(frame64)

// hilo lector
if (transport.read(out64)) {
    // procesar trama
}
val reason = transport.onDisconnect() // una sola vez tras una caida
```

Al cerrar el servicio: `transport.stop()`.

## Handshake AOA

El telefono es el dispositivo USB; la PC es el host y ejecuta el
handshake AOA (C1). El telefono no envia control transfers para
entrar en modo accesorio.

Cadenas (`SEND_STRING`, indice 0..3):

| Indice | Valor |
|---:|---|
| 0 | `MandoPC` |
| 1 | `MandoPC` |
| 2 | `Mando PC gamepad` |
| 3 | `1.0` |

El filtro `res/xml/accessory_filter.xml` de la app usa
`manufacturer="MandoPC"`, `model="MandoPC"`, `version="1.0"`.

Tras `ACCESSORY_START`, la PC ve VID `0x18D1` y PID `0x2D00`
(`0x2D01` si ADB sigue activo). El canal de datos son los endpoints
bulk de AOA; en Android se usan los flujos del descriptor devuelto por
`UsbManager.openAccessory`.

## Verificacion

```
gradle --no-daemon testDebugUnitTest
```

Requiere JDK 17 y Android SDK 34. Las pruebas cubren la cola circular
y el reensamblado; el transporte real necesita hardware.
