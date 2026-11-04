# UI core — B1

Logica pura del layout Xbox: geometria, hit-test, multitouch,
zona muerta y emision de `InputEvent`. No depende del SDK de Android,
asi que se prueba en JVM.

- `LayoutGeometry.kt`: zonas y rectangulos del layout en horizontal.
- `TouchPadController.kt`: maquina de estados de punteros.
- `PadRenderState.kt`: estado que dibuja la vista.

Convenciones:
- Stick: `-32768..32767`, `Y` positivo hacia arriba (protocolo).
- Zona muerta del 15 % aplicada aqui, no en el daemon.
- Gatillos LT/RT digitales a `65535` mientras se tocan.
- Touchpad central normalizado `0..65535`.
- Hasta 10 dedos simultaneos; ids de puntero 0..31.

Verificacion (requiere Gradle y JDK 17):

```
gradle test
```

Pruebas cubiertas: 10 dedos, dos dedos en el mismo boton, zona
muerta, Y del protocolo, cruceta diagonal, touchpad, gatillos,
cancelacion y 10 000 movimientos con promedio menor a 5 ms.
