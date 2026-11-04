import struct
import sys

MAGIC = b"MNDO"
VERSION = 1
FRAME_SIZE = 64
HEADER = struct.Struct("<4sBBHII")

HELLO = 1
HELLO_ACK = 2
INPUT = 3
PING = 4
PONG = 5
RUMBLE = 6
BYE = 7
ERROR = 8

TYPE_NAMES = {
    1: "HELLO",
    2: "HELLO_ACK",
    3: "INPUT",
    4: "PING",
    5: "PONG",
    6: "RUMBLE",
    7: "BYE",
    8: "ERROR",
}

HELLO_PAYLOAD = struct.Struct("<Q16sBHBBB18s")
HELLO_ACK_PAYLOAD = struct.Struct("<QBBBBB35s")
INPUT_PAYLOAD = struct.Struct("<QIhhhhHHhhhhhhBBHHHBBBx")
PING_PAYLOAD = struct.Struct("<Q")
PONG_PAYLOAD = struct.Struct("<QQ")
RUMBLE_PAYLOAD = struct.Struct("<HHHB")
BYE_PAYLOAD = struct.Struct("<B")
ERROR_PAYLOAD = struct.Struct("<HB45s")

INPUT_FIELDS = [
    "timestamp_us",
    "buttons",
    "lx",
    "ly",
    "rx",
    "ry",
    "lt",
    "rt",
    "gx",
    "gy",
    "gz",
    "ax",
    "ay",
    "az",
    "touch_active",
    "touch_id",
    "touch_x",
    "touch_y",
    "touch_pressure",
    "battery",
    "link",
    "rtt_ms",
]


def build_frame(msg_type, session_id=0, seq=0, payload=b"", flags=0):
    if msg_type not in TYPE_NAMES:
        raise ValueError("unknown message type")
    capacity = FRAME_SIZE - HEADER.size
    if len(payload) > capacity:
        raise ValueError("payload exceeds frame capacity")
    payload = payload + b"\x00" * (capacity - len(payload))
    return HEADER.pack(MAGIC, VERSION, msg_type, flags, session_id, seq) + payload


def parse_frame(frame):
    if len(frame) != FRAME_SIZE:
        raise ValueError("frame must be exactly 64 bytes")
    magic, version, msg_type, flags, session_id, seq = HEADER.unpack_from(frame, 0)
    if magic != MAGIC:
        raise ValueError("bad magic")
    if version != VERSION:
        raise ValueError("unsupported version")
    if msg_type not in TYPE_NAMES:
        raise ValueError("unknown message type")
    return {
        "magic": magic.decode("ascii"),
        "version": version,
        "type": TYPE_NAMES[msg_type],
        "type_id": msg_type,
        "flags": flags,
        "session_id": session_id,
        "seq": seq,
        "payload": frame[HEADER.size:],
    }


def decode_hello(payload):
    ts, uuid, slot, caps, pmin, pmax, mlen, model = HELLO_PAYLOAD.unpack(payload)
    return {
        "timestamp_us": ts,
        "uuid": uuid.hex(),
        "requested_slot": slot,
        "capabilities": caps,
        "proto_min": pmin,
        "proto_max": pmax,
        "model": model[:mlen].decode("utf-8", "replace"),
    }


def decode_hello_ack(payload):
    ts, slot, status, proto, max_players, nlen, name = HELLO_ACK_PAYLOAD.unpack(payload)
    return {
        "timestamp_us": ts,
        "assigned_slot": slot,
        "status": status,
        "host_proto": proto,
        "max_players": max_players,
        "name": name[:nlen].decode("utf-8", "replace"),
    }


def decode_input(payload):
    return dict(zip(INPUT_FIELDS, INPUT_PAYLOAD.unpack(payload)))


def decode_ping(payload):
    return {"client_ts_us": PING_PAYLOAD.unpack_from(payload, 0)[0]}


def decode_pong(payload):
    client_ts, host_ts = PONG_PAYLOAD.unpack_from(payload, 0)
    return {"client_ts_us": client_ts, "host_ts_us": host_ts}


def make_hello(session_id=0, seq=0, timestamp_us=1000, uuid_bytes=None,
               requested_slot=0, capabilities=0b1111, proto_min=1,
               proto_max=1, model="Edge 30"):
    if uuid_bytes is None:
        uuid_bytes = bytes(range(16))
    model_bytes = model.encode("utf-8")
    payload = HELLO_PAYLOAD.pack(
        timestamp_us, uuid_bytes, requested_slot, capabilities,
        proto_min, proto_max, len(model_bytes), model_bytes,
    )
    return build_frame(HELLO, session_id, seq, payload)


def make_hello_ack(session_id=1, seq=0, timestamp_us=2000, assigned_slot=1,
                   status=0, host_proto=1, max_players=4, name="fedora-pc"):
    name_bytes = name.encode("utf-8")
    payload = HELLO_ACK_PAYLOAD.pack(
        timestamp_us, assigned_slot, status, host_proto,
        max_players, len(name_bytes), name_bytes,
    )
    return build_frame(HELLO_ACK, session_id, seq, payload)


def make_input(session_id=1, seq=10, timestamp_us=1000, buttons=0,
               lx=0, ly=0, rx=0, ry=0, lt=0, rt=0,
               gx=0, gy=0, gz=0, ax=0, ay=0, az=0,
               touch_active=0, touch_id=0, touch_x=0, touch_y=0,
               touch_pressure=0, battery=100, link=100, rtt_ms=0):
    payload = INPUT_PAYLOAD.pack(
        timestamp_us, buttons, lx, ly, rx, ry, lt, rt,
        gx, gy, gz, ax, ay, az, touch_active, touch_id,
        touch_x, touch_y, touch_pressure, battery, link, rtt_ms,
    )
    return build_frame(INPUT, session_id, seq, payload)


def make_ping(session_id=1, seq=0, client_ts_us=5000):
    return build_frame(PING, session_id, seq, PING_PAYLOAD.pack(client_ts_us))


def make_pong(session_id=1, seq=0, client_ts_us=5000, host_ts_us=7000):
    return build_frame(PONG, session_id, seq, PONG_PAYLOAD.pack(client_ts_us, host_ts_us))


def dump(name, frame):
    parsed = parse_frame(frame)
    print(f"{name}: {len(frame)} bytes, type={parsed['type']}, session={parsed['session_id']}, seq={parsed['seq']}")
    print("  hex  : " + frame.hex(" "))
    if parsed["type"] == "HELLO":
        print("  data : " + repr(decode_hello(parsed["payload"])))
    elif parsed["type"] == "HELLO_ACK":
        print("  data : " + repr(decode_hello_ack(parsed["payload"])))
    elif parsed["type"] == "INPUT":
        print("  data : " + repr(decode_input(parsed["payload"])))
    elif parsed["type"] == "PING":
        print("  data : " + repr(decode_ping(parsed["payload"])))
    elif parsed["type"] == "PONG":
        print("  data : " + repr(decode_pong(parsed["payload"])))


def selftest():
    frames = [
        ("HELLO", make_hello()),
        ("HELLO_ACK", make_hello_ack()),
        ("INPUT", make_input(buttons=0x00000001)),
        ("PING", make_ping()),
        ("PONG", make_pong()),
    ]
    for name, frame in frames:
        assert len(frame) == FRAME_SIZE
        dump(name, frame)
    bad = bytearray(frames[0][1])
    bad[0] = 0
    try:
        parse_frame(bytes(bad))
    except ValueError as exc:
        print(f"REJECT bad magic: {exc}")
    else:
        raise AssertionError("bad magic was accepted")
    print("SELFTEST OK")


def main(argv):
    if len(argv) == 2 and argv[1] == "--selftest":
        selftest()
        return 0
    if len(argv) > 1:
        raw = bytes.fromhex(" ".join(argv[1:]))
        parsed = parse_frame(raw)
        print(parsed["type"], parsed["session_id"], parsed["seq"])
        return 0
    print("usage: python protocol_v1.py --selftest")
    print("       python protocol_v1.py <64 bytes in hex>")
    return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
