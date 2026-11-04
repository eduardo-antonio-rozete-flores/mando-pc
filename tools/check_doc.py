import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import protocol_v1 as p

doc = Path(__file__).resolve().parent.parent / "docs" / "PROTOCOL.md"
text = doc.read_text(encoding="utf-8")
blocks = re.findall(r"```\s*\n(.*?)```", text, re.S)

validated = 0
for block in blocks:
    tokens = block.split()
    if not tokens:
        continue
    if not all(re.fullmatch(r"[0-9a-fA-F]{2}", token) for token in tokens):
        continue
    raw = bytes.fromhex(" ".join(tokens))
    if len(raw) != p.FRAME_SIZE:
        continue
    parsed = p.parse_frame(raw)
    print(f"OK {parsed['type']:<9} session={parsed['session_id']} seq={parsed['seq']} bytes={len(raw)}")
    if parsed["type"] == "INPUT":
        print("   " + repr(p.decode_input(parsed["payload"])))
    elif parsed["type"] == "HELLO":
        print("   " + repr(p.decode_hello(parsed["payload"])))
    elif parsed["type"] == "HELLO_ACK":
        print("   " + repr(p.decode_hello_ack(parsed["payload"])))
    validated += 1

print(f"validated {validated} frames")
if validated != 5:
    raise SystemExit("expected 5 frames in PROTOCOL.md")
