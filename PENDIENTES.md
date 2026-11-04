# Estado y pendientes

Actualizado: 2026-11-03.

Este documento resume que ya funciona, que falta y como compilar o
probar cada parte. El plan completo esta en `PLAN_DE_TRABAJO.txt` y el
alcance en `OBJETIVO_MANDO_PC.txt`.

## Estado actual

| Parte | Estado | Entregable |
|---|---|---|
| A1 | Hecho | `docs/PROTOCOL.md`, `tools/protocol_v1.py` |
| A2 | Hecho | `docs/INTERFACES.md`, `daemon/`, `android/contracts/` |
| A3 | Hecho | Estructura, `.gitignore`, `Makefile`, CI |
| B1 | Hecho | Layout Xbox y captura tactil, `android/ui-core/` |
| B2 | Hecho | Transporte AOA, `android/transport/` |
| B3 | Hecho | Servicio, UUID y slots, `android/session/` y `MandoService` |
| C1 | Siguiente | Transporte AOA host en Fedora |
| C2 | Pendiente | Backend uinput multigamepad |
| C3 | Pendiente | Orquestador de slots |
| C4 | Pendiente | Latencia y logging |
| D1 | Pendiente | Arnes de pruebas sin telefonos |
| D2 | Pendiente | Pruebas con hardware real |
| D3 | Pendiente | Estres y reconexion |
| E1 | Pendiente | Empaquetado Fedora |
| E2 | Pendiente | Documentacion de usuario |

## Que se puede probar hoy

El APK de B3 ya se puede instalar en el Edge 30 y permite validar:

- Layout Xbox, multitouch y respuesta tactil.
- Servicio en primer plano, notificacion y wakelock.
- UUID persistente y selector de slot (AUTO, 1, 2, 3, 4).
- Reconexion de la app al volver el cable.

Lo que **todavia no funciona de punta a punta** es el envio real de
`HELLO` e `INPUT`: el handshake AOA lo inicia la PC y esa parte es C1.
Sin C1 el telefono no entra en modo accesorio y el servicio se queda
esperando a la PC. La prueba completa llega con C1 + C2 + C3.

## Pendientes por parte

### C1 - Transporte AOA host (Fedora)

- Enumerar los accesorios Android con libusb/rusb.
- Hacer el handshake AOA (`51 GET_PROTOCOL`, `52 SEND_STRING`,
  `53 START_ACCESSORY`).
- Abrir los endpoints bulk de 64 bytes y leer tramas v1.
- Detectar conexion y desconexion en menos de 1 s.
- Soportar 4 dispositivos simultaneos sin perder paquetes.

### C2 - Backend uinput multigamepad

- Crear 4 gamepads virtuales persistentes al arrancar.
- Mapear `PlayerState` a eventos evdev (botones, ejes, gatillos).
- Invertir Y para evdev, segun `docs/PROTOCOL.md`.
- Dejar el slot en neutro al desconectar, sin destruirlo.

### C3 - Orquestador de slots

- Mapear UUID persistente a slot 1..4.
- Implementar `HELLO`/`HELLO_ACK`, `session_id`, timeouts y
  `PING`/`PONG`.
- Reasignar el mismo slot al reconectar.
- Nunca asignar dos telefonos al mismo slot.
- Persistir la asignacion en configuracion.

### C4 - Latencia y logging

- Medir RTT por jugador con `PING`/`PONG`.
- Registrar eventos utiles para diagnostico (conexion, slots, errores).
- Exponer un comando de estado con la latencia por jugador.

### D1 - Arnes sin telefonos

- Simulador de 4 telefonos que hable el protocolo v1.
- Transporte falso para probar C1-C4 y B1-B3 sin hardware.

### D2 - Pruebas con hardware real

- 2 a 4 telefonos con hub USB alimentado.
- Matriz de casos con `evtest`, `jstest`, Steam/Proton y emuladores.
- Reporte de bugs y latencia medida.

### D3 - Estres y reconexion

- Ciclos de conectar/desconectar en caliente.
- 1 h de uso, consumo de CPU/memoria y comportamiento termico.

### E1 - Empaquetado Fedora

- RPM o COPR, regla udev y servicio systemd de usuario.
- Politica SELinux o etiquetado del binario.

### E2 - Documentacion

- Guia de instalacion, uso y troubleshooting.
- Problemas frecuentes: SELinux, hub, ADB, permisos de uinput.

## Como compilar y probar

### Verificacion general

```
py tools/check_all.py
```

### Protocolo y contratos

```
py tools/protocol_v1.py --selftest
py tools/check_doc.py
py tools/contract_v1.py --test
```

### Android

Con JDK 17+ y Android SDK 34:

```
make android-session
make android-transport
make android-app
```

APK resultante:

```
android/app/build/outputs/apk/debug/mando-app-debug.apk
```

Instalacion por ADB:

```
adb install -r android/app/build/outputs/apk/debug/mando-app-debug.apk
```

En esta maquina Windows el build local se hizo con JDK 21 y Gradle
8.13; la CI usa JDK 17 y Gradle 8.5. Si `gradle` no esta en el PATH,
usar el `gradle.bat` de la distribucion de Gradle instalada.

### Rust

```
cargo test --manifest-path daemon/Cargo.toml
cargo clippy --manifest-path daemon/Cargo.toml -- -D warnings
```

`cargo` no esta instalado en esta maquina Windows; la compilacion Rust
se valida en la CI o en Fedora.

## Pendiente de hardware

- B1: 10 dedos reales y respuesta tactil en el Edge 30.
- B2: handshake real con la PC y medicion de frecuencia/reconexion.
- B3: 1 h en primer plano, notificacion, wakelock y handshake real.
- D2/D3: 2 a 4 telefonos, hub alimentado, 30 min sin desconexiones y
  latencia de enlace menor a 10 ms.

## Siguiente paso

Implementar **C1** en `daemon/`: transporte AOA host con libusb/rusb,
handshake, lectura bulk y deteccion de hot-plug contra los contratos y
mocks existentes.
