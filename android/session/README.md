# Sesión, identidad y slots (B3)

Lógica pura (JVM, sin SDK de Android) para el servicio de B3.

## Piezas

- `FrameCodec`: construye y valida tramas de 64 bytes de v1
  (`HELLO`, `INPUT`, `PING`, `BYE`) y decodifica `HELLO_ACK`, `PONG`
  y `RUMBLE`.
- `SessionEngine`: máquina de estados Desconectado → Handshake →
  Streaming → Error; reintenta `HELLO`, mide RTT, limita `INPUT` a
  la tasa configurada y reinicia la sesión al reconectar.
- `Identity`: UUID persistente de 16 bytes y slot pedido; incluye
  `InMemoryIdentityStore` para pruebas.
- `android/app/PreferencesIdentityStore`: implementación real sobre
  `SharedPreferences`.

## Uso desde el servicio

```kotlin
val engine = SessionEngine(uuid, FrameSink { transport.write(it) }, model = Build.MODEL)
engine.onRumble = { rumble -> vibrar(rumble) }
engine.beginHandshake(nowUs)
engine.tick(nowUs, MandoRuntime.snapshot())
engine.onFrame(frame, nowUs)
```

## Verificación

```
gradle --no-daemon test
```

Cubre los vectores de `docs/PROTOCOL.md`, handshake, slot ocupado,
timeout, PING/PONG, RUMBLE, límite de tasa, reconexión y UUID.
