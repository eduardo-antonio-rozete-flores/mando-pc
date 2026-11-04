"""Arnes ejecutable de los contratos A2 (docs/INTERFACES.md).

Este archivo es la implementacion de referencia en Python de los tres
contratos. Sirve para verificar sin toolchain de Rust ni Kotlin; las
versiones Rust y Kotlin viven en daemon/ y android/contracts/.

Uso:
    py tools/contract_v1.py --test
"""

from __future__ import annotations

import abc
import sys
from collections import deque
from dataclasses import dataclass, field, replace
from enum import Enum, IntEnum
from pathlib import Path
from typing import Iterable

sys.path.insert(0, str(Path(__file__).resolve().parent))
import protocol_v1 as proto

FRAME_SIZE = proto.FRAME_SIZE
MAX_SLOTS = 4


class ButtonId(IntEnum):
    A = 0
    B = 1
    X = 2
    Y = 3
    LB = 4
    RB = 5
    Back = 6
    Start = 7
    Guide = 8
    L3 = 9
    R3 = 10
    DpadUp = 11
    DpadDown = 12
    DpadLeft = 13
    DpadRight = 14
    Touchpad = 15
    Capture = 16

    @property
    def mask(self) -> int:
        return 1 << int(self)


class StickId(Enum):
    Left = "left"
    Right = "right"


class TriggerId(Enum):
    Left = "left"
    Right = "right"


class DisconnectReason(Enum):
    Normal = "normal"
    AppClosed = "app_closed"
    CableRemoved = "cable_removed"
    Timeout = "timeout"
    Error = "error"


@dataclass(frozen=True)
class TouchState:
    active: bool = False
    id: int = 0
    x: int = 0
    y: int = 0
    pressure: int = 0


@dataclass(frozen=True)
class PlayerState:
    timestamp_us: int = 0
    seq: int = 0
    buttons: int = 0
    lx: int = 0
    ly: int = 0
    rx: int = 0
    ry: int = 0
    lt: int = 0
    rt: int = 0
    gyro: tuple[int, int, int] = (0, 0, 0)
    accel: tuple[int, int, int] = (0, 0, 0)
    touch: TouchState = field(default_factory=TouchState)
    battery: int = 255
    link: int = 0
    rtt_ms: int = 0

    @classmethod
    def neutral(cls) -> "PlayerState":
        return cls()

    def is_neutral(self) -> bool:
        return self == type(self)()

    @classmethod
    def from_protocol(cls, data: dict, seq: int) -> "PlayerState":
        return cls(
            timestamp_us=data["timestamp_us"],
            seq=seq,
            buttons=data["buttons"],
            lx=data["lx"],
            ly=data["ly"],
            rx=data["rx"],
            ry=data["ry"],
            lt=data["lt"],
            rt=data["rt"],
            gyro=(data["gx"], data["gy"], data["gz"]),
            accel=(data["ax"], data["ay"], data["az"]),
            touch=TouchState(
                active=bool(data["touch_active"]),
                id=data["touch_id"],
                x=data["touch_x"],
                y=data["touch_y"],
                pressure=data["touch_pressure"],
            ),
            battery=data["battery"],
            link=data["link"],
            rtt_ms=data["rtt_ms"],
        )

    def input_kwargs(self, session_id: int, seq: int) -> dict:
        return {
            "session_id": session_id,
            "seq": seq,
            "timestamp_us": self.timestamp_us,
            "buttons": self.buttons,
            "lx": self.lx,
            "ly": self.ly,
            "rx": self.rx,
            "ry": self.ry,
            "lt": self.lt,
            "rt": self.rt,
            "gx": self.gyro[0],
            "gy": self.gyro[1],
            "gz": self.gyro[2],
            "ax": self.accel[0],
            "ay": self.accel[1],
            "az": self.accel[2],
            "touch_active": int(self.touch.active),
            "touch_id": self.touch.id,
            "touch_x": self.touch.x,
            "touch_y": self.touch.y,
            "touch_pressure": self.touch.pressure,
            "battery": self.battery,
            "link": self.link,
            "rtt_ms": self.rtt_ms,
        }


@dataclass(frozen=True)
class ButtonEvent:
    id: ButtonId
    pressed: bool


@dataclass(frozen=True)
class StickEvent:
    id: StickId
    x: int
    y: int


@dataclass(frozen=True)
class TriggerEvent:
    id: TriggerId
    value: int


@dataclass(frozen=True)
class TouchEvent:
    state: TouchState


@dataclass(frozen=True)
class GyroEvent:
    values: tuple[int, int, int]


@dataclass(frozen=True)
class AccelEvent:
    values: tuple[int, int, int]


@dataclass(frozen=True)
class BatteryEvent:
    value: int


@dataclass(frozen=True)
class LinkEvent:
    value: int


InputEvent = (
    ButtonEvent
    | StickEvent
    | TriggerEvent
    | TouchEvent
    | GyroEvent
    | AccelEvent
    | BatteryEvent
    | LinkEvent
)


def apply_event(state: PlayerState, event: InputEvent) -> PlayerState:
    if isinstance(event, ButtonEvent):
        buttons = (
            state.buttons | event.id.mask
            if event.pressed
            else state.buttons & ~event.id.mask
        )
        return replace(state, buttons=buttons)
    if isinstance(event, StickEvent):
        if event.id is StickId.Left:
            return replace(state, lx=event.x, ly=event.y)
        return replace(state, rx=event.x, ry=event.y)
    if isinstance(event, TriggerEvent):
        if event.id is TriggerId.Left:
            return replace(state, lt=event.value)
        return replace(state, rt=event.value)
    if isinstance(event, TouchEvent):
        return replace(state, touch=event.state)
    if isinstance(event, GyroEvent):
        return replace(state, gyro=event.values)
    if isinstance(event, AccelEvent):
        return replace(state, accel=event.values)
    if isinstance(event, BatteryEvent):
        return replace(state, battery=event.value)
    if isinstance(event, LinkEvent):
        return replace(state, link=event.value)
    raise TypeError(f"unknown event: {event!r}")


class TransportError(Exception):
    pass


class TransportNotOpen(TransportError):
    pass


class TransportDisconnected(TransportError):
    def __init__(self, reason: DisconnectReason):
        super().__init__(f"transport disconnected: {reason.value}")
        self.reason = reason


class Transport(abc.ABC):
    @abc.abstractmethod
    def open(self) -> None:
        raise NotImplementedError

    @abc.abstractmethod
    def close(self) -> None:
        raise NotImplementedError

    @abc.abstractmethod
    def read(self, out: bytearray) -> bool:
        raise NotImplementedError

    @abc.abstractmethod
    def write(self, frame: bytes) -> None:
        raise NotImplementedError

    @abc.abstractmethod
    def on_disconnect(self) -> DisconnectReason | None:
        raise NotImplementedError


class GamepadBackend(abc.ABC):
    @abc.abstractmethod
    def create_slot(self, slot: int) -> None:
        raise NotImplementedError

    @abc.abstractmethod
    def update(self, slot: int, state: PlayerState) -> None:
        raise NotImplementedError

    @abc.abstractmethod
    def reset(self, slot: int) -> None:
        raise NotImplementedError

    @abc.abstractmethod
    def destroy(self, slot: int) -> None:
        raise NotImplementedError

    @abc.abstractmethod
    def slots(self) -> list[int]:
        raise NotImplementedError


class InputSource(abc.ABC):
    @abc.abstractmethod
    def poll(self, now_us: int) -> InputEvent | None:
        raise NotImplementedError


class MockTransport(Transport):
    def __init__(
        self,
        frames: Iterable[bytes] | None = None,
        *,
        label: str = "mock",
        session_id: int = 1,
        interval_us: int = 1_000,
    ):
        self.label = label
        self.session_id = session_id
        self.interval_us = interval_us
        self._frames = deque(frames or [])
        self._writes: list[bytes] = []
        self._opened = False
        self._disconnect: DisconnectReason | None = None
        self.clock_us = 0
        self.next_at_us = 0
        self._seq = 1

    def open(self) -> None:
        self._opened = True

    def close(self) -> None:
        self._opened = False

    def read(self, out: bytearray) -> bool:
        if not self._opened:
            raise TransportNotOpen("transport is not open")
        if self._disconnect is not None:
            raise TransportDisconnected(self._disconnect)
        if not self._frames:
            return False
        frame = self._frames.popleft()
        if len(frame) != FRAME_SIZE:
            raise TransportError(f"frame must be {FRAME_SIZE} bytes")
        out[:FRAME_SIZE] = frame
        return True

    def write(self, frame: bytes) -> None:
        if not self._opened:
            raise TransportNotOpen("transport is not open")
        if len(frame) != FRAME_SIZE:
            raise TransportError(f"frame must be {FRAME_SIZE} bytes")
        self._writes.append(bytes(frame))

    def on_disconnect(self) -> DisconnectReason | None:
        reason = self._disconnect
        self._disconnect = None
        return reason

    def disconnect(self, reason: DisconnectReason) -> None:
        self._disconnect = reason

    def advance(self, delta_us: int) -> None:
        self.clock_us += delta_us
        while self.next_at_us < self.clock_us:
            frame = proto.make_input(
                session_id=self.session_id,
                seq=self._seq,
                timestamp_us=self.next_at_us,
            )
            self._frames.append(frame)
            self._seq = (self._seq + 1) & 0xFFFFFFFF
            self.next_at_us += self.interval_us

    @property
    def writes(self) -> list[bytes]:
        return list(self._writes)


class AoaMockTransport(MockTransport):
    def __init__(self, frames: Iterable[bytes] | None = None):
        super().__init__(frames, label="aoa")


class TetheringMockTransport(MockTransport):
    def __init__(self, frames: Iterable[bytes] | None = None):
        super().__init__(frames, label="tethering")


class MockGamepad(GamepadBackend):
    def __init__(self, name: str = "mock", print_updates: bool = False):
        self.name = name
        self.print_updates = print_updates
        self.created: list[int] = []
        self.updates: list[tuple[int, PlayerState]] = []
        self.resets: list[int] = []
        self.destroyed: list[int] = []

    def create_slot(self, slot: int) -> None:
        if slot not in range(1, MAX_SLOTS + 1):
            raise ValueError(f"invalid slot: {slot}")
        if slot not in self.created:
            self.created.append(slot)
        if slot in self.destroyed:
            self.destroyed.remove(slot)

    def update(self, slot: int, state: PlayerState) -> None:
        if slot not in self.slots():
            raise ValueError(f"slot {slot} does not exist")
        self.updates.append((slot, state))
        if self.print_updates:
            print(f"[{self.name}] slot {slot}: {state}")

    def reset(self, slot: int) -> None:
        if slot not in self.slots():
            raise ValueError(f"slot {slot} does not exist")
        self.resets.append(slot)
        neutral = PlayerState.neutral()
        self.updates.append((slot, neutral))
        if self.print_updates:
            print(f"[{self.name}] slot {slot}: reset -> {neutral}")

    def destroy(self, slot: int) -> None:
        if slot not in self.slots():
            raise ValueError(f"slot {slot} does not exist")
        if slot not in self.destroyed:
            self.destroyed.append(slot)

    def slots(self) -> list[int]:
        return sorted(slot for slot in self.created if slot not in self.destroyed)


class UinputMockGamepad(MockGamepad):
    def __init__(self, print_updates: bool = False):
        super().__init__("uinput", print_updates)


class UhidMockGamepad(MockGamepad):
    def __init__(self, print_updates: bool = False):
        super().__init__("uhid", print_updates)


class MockInputSource(InputSource):
    def __init__(self, events: Iterable[tuple[int, InputEvent]] = ()):
        self._events = deque(sorted(events, key=lambda item: item[0]))

    def push(self, at_us: int, event: InputEvent) -> None:
        self._events.append((at_us, event))

    def poll(self, now_us: int) -> InputEvent | None:
        if not self._events:
            return None
        at_us, event = self._events[0]
        if at_us > now_us:
            return None
        self._events.popleft()
        return event


class SlotOrchestrator:
    """Arnés minimo: une Transport con GamepadBackend.

    No es el orquestador real de C3; solo demuestra que las capas se
    pueden sustituir sin cambiar este codigo.
    """

    def __init__(self, backend: GamepadBackend):
        self.backend = backend
        for slot in range(1, MAX_SLOTS + 1):
            backend.create_slot(slot)

    def pump(self, transport: Transport, slot: int = 1) -> int:
        out = bytearray(FRAME_SIZE)
        count = 0
        while transport.read(out):
            parsed = proto.parse_frame(bytes(out))
            if parsed["type"] != "INPUT":
                continue
            state = PlayerState.from_protocol(
                proto.decode_input(parsed["payload"]), parsed["seq"]
            )
            self.backend.update(slot, state)
            count += 1
        return count

    def disconnect(self, slot: int) -> None:
        self.backend.reset(slot)


def sample_frames() -> list[bytes]:
    return [
        proto.make_input(
            session_id=1,
            seq=seq,
            timestamp_us=seq * 1_000,
            buttons=1 if seq % 2 else 0,
        )
        for seq in range(1, 4)
    ]


def test_transport_substitution() -> None:
    results = []
    for transport_cls in (AoaMockTransport, TetheringMockTransport):
        transport = transport_cls(sample_frames())
        transport.open()
        backend = MockGamepad(transport.label)
        SlotOrchestrator(backend).pump(transport)
        results.append(backend.updates)
    assert results[0] == results[1], "transport swap changed backend updates"
    assert len(results[0]) == 3
    print(f"  transportos probados: aoa, tethering; updates={len(results[0])}")


def test_backend_substitution() -> None:
    results = []
    for backend in (UinputMockGamepad(), UhidMockGamepad()):
        transport = AoaMockTransport(sample_frames())
        transport.open()
        SlotOrchestrator(backend).pump(transport)
        results.append(backend.updates)
    assert results[0] == results[1], "backend swap changed update sequence"
    print(f"  backends probados: uinput, uhid; updates={len(results[0])}")


def test_1000_hz() -> None:
    transport = AoaMockTransport()
    transport.open()
    transport.advance(1_000_000)
    backend = UinputMockGamepad()
    count = SlotOrchestrator(backend).pump(transport)
    assert count == 1000, f"expected 1000 frames, got {count}"
    assert transport.clock_us == 1_000_000
    print(f"  frames en 1 s simulado: {count}")


def test_slots_stay_alive_and_neutral() -> None:
    backend = UinputMockGamepad()
    orchestrator = SlotOrchestrator(backend)
    assert backend.slots() == [1, 2, 3, 4]
    orchestrator.disconnect(2)
    assert backend.slots() == [1, 2, 3, 4], "slot 2 was destroyed"
    assert backend.updates[-1][1].is_neutral(), "slot 2 was not neutral"
    backend.update(2, PlayerState(buttons=1))
    assert backend.slots() == [1, 2, 3, 4], "slot 2 was duplicated"
    print(f"  slots vivos tras desconectar: {backend.slots()}")


def test_event_to_frame_roundtrip() -> None:
    state = PlayerState.neutral()
    state = apply_event(state, ButtonEvent(ButtonId.A, True))
    state = apply_event(state, StickEvent(StickId.Left, 12_000, -4_000))
    state = apply_event(state, TriggerEvent(TriggerId.Right, 65_535))
    state = apply_event(state, TouchEvent(TouchState(True, 3, 40_000, 20_000, 900)))
    state = apply_event(state, GyroEvent((100, -200, 300)))
    state = apply_event(state, AccelEvent((10, 20, 30)))
    state = apply_event(state, BatteryEvent(87))
    state = apply_event(state, LinkEvent(99))

    frame = proto.make_input(**state.input_kwargs(session_id=1, seq=7))
    assert len(frame) == FRAME_SIZE
    decoded = proto.decode_input(proto.parse_frame(frame)["payload"])

    assert decoded["buttons"] & ButtonId.A.mask
    assert decoded["lx"] == 12_000
    assert decoded["ly"] == -4_000
    assert decoded["rt"] == 65_535
    assert decoded["touch_active"] == 1
    assert decoded["touch_x"] == 40_000
    assert decoded["gx"] == 100
    assert decoded["az"] == 30
    assert decoded["battery"] == 87
    assert decoded["link"] == 99
    print("  evento -> PlayerState -> INPUT -> decode: OK")


def test_disconnect_is_reported_once() -> None:
    transport = AoaMockTransport()
    transport.open()
    transport.disconnect(DisconnectReason.CableRemoved)
    try:
        transport.read(bytearray(FRAME_SIZE))
    except TransportDisconnected as exc:
        assert exc.reason is DisconnectReason.CableRemoved
    else:
        raise AssertionError("disconnected transport produced a frame")
    assert transport.on_disconnect() is DisconnectReason.CableRemoved
    assert transport.on_disconnect() is None
    print("  desconexion reportada una sola vez")


def test_transport_rejects_wrong_size() -> None:
    transport = AoaMockTransport()
    transport.open()
    try:
        transport.write(b"short")
    except TransportError:
        pass
    else:
        raise AssertionError("bad frame size was accepted")
    print("  tramas de tamano distinto a 64: rechazadas")


def test_input_source_is_non_blocking() -> None:
    source = MockInputSource(
        [
            (10, ButtonEvent(ButtonId.A, True)),
            (20, ButtonEvent(ButtonId.A, False)),
        ]
    )
    state = PlayerState.neutral()
    assert source.poll(5) is None
    event = source.poll(10)
    assert isinstance(event, ButtonEvent)
    state = apply_event(state, event)
    assert state.buttons & ButtonId.A.mask
    assert source.poll(19) is None
    event = source.poll(20)
    assert isinstance(event, ButtonEvent)
    state = apply_event(state, event)
    assert state.buttons == 0
    print("  InputSource no bloquea y respeta orden")


TESTS = [
    test_transport_substitution,
    test_backend_substitution,
    test_1000_hz,
    test_slots_stay_alive_and_neutral,
    test_event_to_frame_roundtrip,
    test_disconnect_is_reported_once,
    test_transport_rejects_wrong_size,
    test_input_source_is_non_blocking,
]


def main(argv: list[str]) -> int:
    if len(argv) == 2 and argv[1] == "--test":
        for test in TESTS:
            print(f"RUN {test.__name__}")
            test()
        print(f"OK {len(TESTS)} contract tests passed")
        return 0
    print("usage: py tools/contract_v1.py --test")
    return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
