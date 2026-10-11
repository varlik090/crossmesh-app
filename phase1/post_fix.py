from pathlib import Path
p = Path("CYPHER_NET_3_5_1_PHASE1_SOURCE.py")
s = p.read_text(encoding="utf-8")
old = "n=ROOM_KDF_N, r=ROOM_KDF_R, p=ROOM_KDF_P, dklen=32,"
new = "n=ROOM_KDF_N, r=ROOM_KDF_R, p=ROOM_KDF_P, maxmem=64 * 1024 * 1024, dklen=32,"
if old not in s:
    raise SystemExit("expected room KDF call not found")
p.write_text(s.replace(old, new, 1), encoding="utf-8")
