import os, sys, tempfile, json, hashlib
os.environ["QT_QPA_PLATFORM"] = "offscreen"
sys.path.insert(0, os.getcwd())

import CYPHER_NET as cn
from PyQt6.QtWidgets import QApplication

app = QApplication([])

def prep(node, nick, room):
    node.room_code = room
    node.my_nick = nick
    node.room_master_key = cn.CryptoSuite.room_master_key(room)
    tk = cn.CryptoSuite.room_subkey(node.room_master_key, b"TOPIC")
    node._room_topic = cn.hmac.new(tk, b"room", cn.hashlib.sha256).hexdigest()[:40]

room = "room-" + cn.secrets.token_urlsafe(24)
a = cn.GlobalRelayNode()
b = cn.GlobalRelayNode()
prep(a, "Alice", room)
prep(b, "Bob", room)
assert a._room_topic == b._room_topic

b._handle_hello(a._hello_body())
a._handle_hello(b._hello_body())
assert "Bob" in a.peers and "Alice" in b.peers
a.peers["Bob"].verified_in_session = True
b.peers["Alice"].verified_in_session = True
received = []
b.signals.message_received.connect(lambda s, t: received.append((s, t)))
p = a._sign_and_encrypt_for("Bob", b"hello", "MSG")
b._handle_packet_v4(p)
assert received == [("Alice", "hello")]
b._handle_packet_v4(p)
assert len(received) == 1

raw1 = cn.GlobalRelayNode._padded_payload_bytes("MSG", b"x", {})
raw2 = cn.GlobalRelayNode._padded_payload_bytes("MSG", b"short message", {})
assert len(raw1) >= 1024
assert len(raw1) == len(raw2)

capt = []
a._publish_enveloped = lambda obj, wait_for_publish=False: capt.append(obj)
policy_events = []
b.enh_signals.room_policy_updated.connect(lambda x: policy_events.append(dict(x)))
a.set_room_policy(True, 5, 2, True)
assert capt and capt[-1]["type"] == "ROOMCFG4"
b._handle_roomcfg4(capt[-1])
assert policy_events
assert policy_events[-1]["timed"] is True
assert policy_events[-1]["duration_min"] == 5
assert policy_events[-1]["message_clear_min"] == 2
assert policy_events[-1]["one_time"] is True

wrong = cn.GlobalRelayNode()
prep(wrong, "Mallory", "wrong-" + cn.secrets.token_urlsafe(24))
wrong_events = []
wrong.enh_signals.room_policy_updated.connect(lambda x: wrong_events.append(x))
wrong._handle_roomcfg4(capt[-1])
assert not wrong_events

salt = os.urandom(16)
code = "ABCD-EFGH-IJKL"
key = cn._cn36_file_key_from_code(code, salt)
fid = "f" * 32
data = b"abc123"
dig = hashlib.sha256(data).hexdigest()
nonce = cn._cn36_file_nonce(key, fid, 0)
aad = cn._cn36_file_aad(fid, 0, 1, dig)
enc = cn.AESGCM(key).encrypt(nonce, data, aad)
assert cn.AESGCM(key).decrypt(nonce, enc, aad) == data

verifier = cn.b64e(cn.hmac.new(key, b"CYPHER_NET_FILE_VERIFY:" + fid.encode(), cn.hashlib.sha256).digest()[:16])
inc = cn.ProtectedIncomingFile(fid, "Alice", "secret.txt", 1, len(data), dig, cn.b64e(salt), verifier, True)
inc.add_chunk(0, enc)
meta = {
    "file_id": fid, "sender": "Alice", "filename": "secret.txt",
    "total_chunks": 1, "total_size": len(data), "sha256": dig,
    "salt_b64": inc.salt_b64, "verifier_b64": inc.verifier_b64,
    "protected": True, "clear_key_b64": "", "temp_path": inc.temp_path,
}
failed = False
try:
    b.decrypt_protected_file(meta, "WRONG-CODE")
except Exception:
    failed = True
assert failed
out = b.decrypt_protected_file(meta, code)
with open(out, "rb") as fh:
    assert fh.read() == data
os.remove(out)

w = cn.CypherNetGUI()
for attr in [
    "btn_join", "btn_disconnect", "btn_send", "btn_file",
    "btn_timed_room", "btn_room_policy", "btn_burn", "btn_pause_file"
]:
    assert hasattr(w, attr), attr
assert w.btn_timed_room.text()
assert w.btn_burn.text()
assert w.msg_input is not None
w.close()

print("CYPHER_NET_3_6_SELFTEST_OK")
