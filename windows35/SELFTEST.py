import json
import sys
from PyQt6.QtCore import QCoreApplication
import CYPHER_NET as cn

app = QCoreApplication([])

def prep(node, nick, room):
    node.room_code = room
    node.my_nick = nick
    node.room_master_key = cn.CryptoSuite.room_master_key(room)
    topic_key = cn.CryptoSuite.room_subkey(node.room_master_key, b"TOPIC")
    node._room_topic = cn.hmac.new(topic_key, b"room", cn.hashlib.sha256).hexdigest()[:40]

room = "audit-" + cn.secrets.token_urlsafe(24)
a = cn.GlobalRelayNode()
b = cn.GlobalRelayNode()
prep(a, "Alice", room)
prep(b, "Bob", room)

assert a._room_topic == b._room_topic
hello_a = a._hello_body()
hello_b = b._hello_body()
public_hello = json.dumps(hello_a)
assert "Alice" not in public_hello
assert a.signing_public.hex() not in public_hello

b._handle_hello(hello_a)
a._handle_hello(hello_b)
assert "Bob" in a.peers and "Alice" in b.peers

wrong = cn.GlobalRelayNode()
prep(wrong, "Mallory", "wrong-" + cn.secrets.token_urlsafe(24))
wrong._handle_hello(hello_a)
assert "Alice" not in wrong.peers

# Acceptance works over the encrypted control channel.
accept_packet = a._v4_encrypt_for("Bob", "CONTROL", b"", {"cmd": "ACCEPT"}, require_verified=False)
b._handle_packet_v4(accept_packet)
assert b.peers["Alice"].verified_in_session
b.peers["Alice"].verified_in_session = True
a.peers["Bob"].verified_in_session = True

received = []
b.signals.message_received.connect(lambda sender, text: received.append((sender, text)))

packet1 = a._sign_and_encrypt_for("Bob", b"hello hardened", "MSG")
outer = json.dumps(packet1)
assert "Alice" not in outer and "Bob" not in outer and "hello hardened" not in outer
b._handle_packet_v4(packet1)
assert received == [("Alice", "hello hardened")]

# Replay is rejected.
b._handle_packet_v4(packet1)
assert len(received) == 1

# Tampering is rejected, without desynchronizing the ratchet permanently.
packet2 = a._sign_and_encrypt_for("Bob", b"tamper-me", "MSG")
bad = dict(packet2)
ct = bad["ct"]
bad["ct"] = ("A" if ct[0] != "A" else "B") + ct[1:]
b._handle_packet_v4(bad)
assert len(received) == 1

packet3 = a._sign_and_encrypt_for("Bob", b"after tamper", "MSG")
b._handle_packet_v4(packet3)
assert received[-1] == ("Alice", "after tamper")

# File metadata is encrypted inside the payload, not exposed in the outer envelope.
file_packet = a._sign_and_encrypt_for(
    "Bob", b"abc", "FILE_CHUNK",
    {
        "file_id": "f1",
        "filename": "secret_contract.pdf",
        "chunk_index": 0,
        "total_chunks": 1,
        "total_size": 3,
        "file_sha256": cn.sha256_hex(b"abc"),
    },
)
file_outer = json.dumps(file_packet)
assert "secret_contract.pdf" not in file_outer and "file_sha256" not in file_outer

# Heartbeat HELLO must not reset ratchet/verification state.
before_send = dict(a.peers["Bob"].send_chain_by_epoch)
before_recv = dict(a.peers["Bob"].recv_chain_by_epoch)
a._handle_hello(b._hello_body())
assert a.peers["Bob"].verified_in_session
assert a.peers["Bob"].send_chain_by_epoch == before_send
assert a.peers["Bob"].recv_chain_by_epoch == before_recv

print("CYPHER_NET_WINDOWS_V4_SELFTEST_OK")
