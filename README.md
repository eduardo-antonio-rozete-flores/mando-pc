# Mando PC

Convertir uno o varios teléfonos Android en mandos de videojuegos para
una PC con Fedora, por cable USB, con baja latencia y un único layout
tipo Xbox.

Alcance actual: Android (Motorola Edge 30 como referencia) + Fedora,
1 a 4 jugadores simultáneos mediante hub USB alimentado.

## Estado

| Parte | Estado | Entregable |
|---|---|---|
| A1 | Hecho | `docs/PROTOCOL.md`, `tools/protocol_v1.py` |
| A2 | Hecho | `docs/INTERFACES.md`, `daemon/`, `android/contracts/` |
| A3 | Hecho | Estructura de repo, `.gitignore`, `Makefile`, CI |
| B1 | Hecho | Layout Xbox, multitouch, `android/ui-core/` y `android/app/` |
| B2 | Hecho | Transporte AOA, `android/transport/` |
| B3 | Hecho | Servicio, UUID y slots: `android/session/` y `MandoService` |

## Pendientes

El detalle de lo que falta y el siguiente paso estan en
[`PENDIENTES.md`](PENDIENTES.md). Hoy se puede probar el APK en el
telefono; el enlace completo necesita C1 (transporte AOA host).

## Estructura

```
android/contracts/   Contratos y mocks Kotlin (JVM, sin SDK de Android)
android/ui-core/     Layout Xbox y captura táctil pura (JVM)
android/session/     Códec v1, handshake, identidad y slots (JVM)
android/transport/   Transporte AOA: accesorio USB, tramas y reconexión
android/app/         APK de B3 con layout, servicio y filtro AOA
daemon/              Contratos y mocks Rust del daemon
docs/                PROTOCOL.md e INTERFACES.md
tools/               Validadores y arnés de contratos en Python
.github/workflows/   CI
```

## Requisitos

- Python 3.11+ para los validadores.
- Rust stable con `cargo`, `rustfmt` y `clippy` para `daemon/`.
- JDK 17+ y Gradle 8.5 para `android/contracts/`, `android/ui-core/` y `android/session/`.
- Android SDK 34 para `android/transport/` y `android/app/`.
- Fedora es el host final; el desarrollo se puede hacer en Windows.

## Verificación

Comprobación única, multiplataforma:

```
py tools/check_all.py
```

Por partes:

```
py tools/protocol_v1.py --selftest
py tools/check_doc.py
py tools/contract_v1.py --test
```

Con Rust, Gradle y Android SDK instalados también se puede usar:

```
make check
make android-session
make android-transport
make android-app
```

`tools/check_all.py` omite Rust, Gradle o el SDK de Android si no están
instalados; la CI siempre ejecuta todos los frentes. El APK queda en
`android/app/build/outputs/apk/debug/mando-app-debug.apk`.

## Uso rápido

1. Compilar el APK con `make android-app` (o `gradle assembleDebug` en
   `android/app/`).
2. Instalarlo en el teléfono con
   `adb install -r android/app/build/outputs/apk/debug/mando-app-debug.apk`.
3. Abrir la app, elegir el slot (AUTO o 1..4) y conectar el teléfono al
   hub USB alimentado.
4. En la PC, iniciar el daemon cuando C1 esté implementado.

Sin C1 el teléfono no entra en modo accesorio: el handshake AOA lo
inicia la PC.

## CI

`.github/workflows/ci.yml` ejecuta en cada push y pull request:

- Python: protocolo, vectores del documento y contratos.
- Rust: `cargo clippy` y `cargo test` (formato informativo).
- Kotlin: `gradle test` en contratos, UI core y sesión.
- Transporte: `gradle testDebugUnitTest` en `android/transport/`.
- Android: `gradle assembleDebug` con SDK 34.

## Documentos

- `OBJETIVO_MANDO_PC.txt`: idea, alcance y arquitectura.
- `PLAN_DE_TRABAJO.txt`: partes, dependencias y estado.
- `PENDIENTES.md`: estado actual, instrucciones y siguiente paso.
- `docs/PROTOCOL.md`: protocolo binario v1.
- `docs/INTERFACES.md`: contratos entre capas v1.
