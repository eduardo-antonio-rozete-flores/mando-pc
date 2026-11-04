# Daemon Mando PC — contratos A2

Solo contiene los contratos de A2: `Transport`, `GamepadBackend` e
`InputSource`, sus tipos de dominio y mocks. No implementa AOA ni
uinput todavia.

- `src/interfaces.rs`: traits y tipos congelados en `docs/INTERFACES.md`.
- `src/mocks.rs`: `MockTransport` (1000 Hz), `MockGamepad` y
  `MockInputSource`.
- `tests/contracts.rs`: TC-1 a TC-6.

Verificacion (requiere cargo, pendiente de A3):

```
cargo test
```
