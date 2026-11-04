# App Android — B3

APK de B3: layout Xbox, captura multitouch, transporte AOA y
servicio en primer plano con identidad y slots.

- `MainActivity`: pantalla completa horizontal, overlay de estado y
  selector de slot (AUTO/1..4), arranca `MandoService` y pide
  `POST_NOTIFICATIONS` en Android 13+.
- `MandoService`: foreground service `connectedDevice`, wakelock,
  notificación persistente, `HELLO`/`HELLO_ACK`, `INPUT` a 1000 Hz,
  `PING`/`PONG`, `RUMBLE` y reconexión.
- `MandoRuntime`: estado del mando compartido entre la vista y el
  servicio; `XboxControllerView` emite `InputEvent` al runtime.
- `PreferencesIdentityStore`: UUID generado una vez y slot pedido
  persistidos en `SharedPreferences`.
- `res/xml/accessory_filter.xml`: cadenas AOA del host
  (`MandoPC` / `MandoPC` / `1.0`).

Permisos: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`,
`CHANGE_NETWORK_STATE` (requisito de Android 14 para el tipo
`connectedDevice`), `WAKE_LOCK`, `VIBRATE` y `POST_NOTIFICATIONS`.

Requisitos: JDK 17+ y Android SDK 34.

```
gradle assembleDebug
```

APK: `build/outputs/apk/debug/mando-app-debug.apk`.

La lógica de sesión está en `android/session` y se prueba sin SDK:

```
cd ../session && gradle --no-daemon test
```
