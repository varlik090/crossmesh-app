package net.cypher.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.math.ec.rfc8032.Ed25519
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

private const val MQTT_HOST = "ssl://broker.hivemq.com:8883"
private const val TOPIC_PREFIX = "cypher_net/v2/room"
private const val KEY_REFRESH_SECONDS = 600L
private const val MAX_AGE_MS = 15L * 60L * 1000L

data class Peer(
    val nick: String,
    val xpub: ByteArray,
    val spub: ByteArray,
    val fingerprint: String,
    var verified: Boolean = false,
    var sendCounter: Long = 0L,
    var recvCounter: Long = -1L,
)

data class ChatLine(val sender: String, val text: String, val system: Boolean = false)

class CryptoBox {
    private val rng = SecureRandom()
    var xpriv = X25519PrivateKeyParameters(rng)
    var epriv = Ed25519PrivateKeyParameters(rng)
    val xpub: ByteArray get() = xpriv.generatePublicKey().encoded
    val epub: ByteArray get() = epriv.generatePublicKey().encoded

    fun reset() {
        xpriv = X25519PrivateKeyParameters(rng)
        epriv = Ed25519PrivateKeyParameters(rng)
    }

    fun sign(data: ByteArray): ByteArray {
        val sig = ByteArray(64)
        epriv.sign(Ed25519.Algorithm.Ed25519, null, data, 0, data.size, sig, 0)
        return sig
    }

    fun verify(pub: ByteArray, data: ByteArray, sig: ByteArray): Boolean =
        Ed25519PublicKeyParameters(pub, 0).verify(
            Ed25519.Algorithm.Ed25519, null, data, 0, data.size, sig, 0
        )

    fun derive(peerPub: ByteArray, room: String, epoch: Long): ByteArray {
        val shared = ByteArray(32)
        xpriv.generateSecret(X25519PublicKeyParameters(peerPub, 0), shared, 0)
        val ordered = if (compareBytes(xpub, peerPub) <= 0) xpub + peerPub else peerPub + xpub
        val salt = sha256(("CYPHER_NET_ROOM:" + room).toByteArray(StandardCharsets.UTF_8))
        val info = "CYPHER_NET_X25519_PAIRWISE_V2:".toByteArray(StandardCharsets.UTF_8) +
            ordered + (":" + epoch).toByteArray(StandardCharsets.US_ASCII)
        return hkdf(shared, salt, info, 32)
    }

    fun encrypt(key: ByteArray, plain: ByteArray, aad: ByteArray): Pair<ByteArray, ByteArray> {
        val nonce = ByteArray(12).also { rng.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        c.updateAAD(aad)
        return nonce to c.doFinal(plain)
    }

    fun decrypt(key: ByteArray, nonce: ByteArray, ct: ByteArray, aad: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        c.updateAAD(aad)
        return c.doFinal(ct)
    }

    private fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, len: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)
        val out = ByteArray(len)
        var t = ByteArray(0)
        var pos = 0
        var ctr = 1
        while (pos < len) {
            mac.init(SecretKeySpec(prk, "HmacSHA256"))
            mac.update(t)
            mac.update(info)
            mac.update(ctr.toByte())
            t = mac.doFinal()
            val n = minOf(t.size, len - pos)
            System.arraycopy(t, 0, out, pos, n)
            pos += n
            ctr++
        }
        return out
    }

    private fun compareBytes(a: ByteArray, b: ByteArray): Int {
        for (i in 0 until minOf(a.size, b.size)) {
            val av = a[i].toInt() and 0xff
            val bv = b[i].toInt() and 0xff
            if (av != bv) return av - bv
        }
        return a.size - b.size
    }
}

class CypherNode(
    private val onLine: (ChatLine) -> Unit,
    private val onPeers: (List<Peer>) -> Unit,
    private val onRequest: (String, String) -> Unit,
    private val onState: (String) -> Unit,
) {
    private val crypto = CryptoBox()
    private var client: MqttClient? = null
    private var room = ""
    private var nick = ""
    private val peers = linkedMapOf<String, Peer>()
    private val blocked = mutableSetOf<String>()
    private val seen = ArrayDeque<String>()

    fun connect(roomCode: String, nickname: String) {
        disconnect()
        room = roomCode.trim()
        nick = nickname.trim()
        require(room.length >= 8)
        require(nick.isNotBlank())
        crypto.reset()
        val topic = TOPIC_PREFIX + "/" + sha256Hex(room.toByteArray()).take(32)
        val c = MqttClient(MQTT_HOST, "cna-" + UUID.randomUUID().toString().take(10), MemoryPersistence())
        c.setCallback(object : MqttCallback {
            override fun connectionLost(cause: Throwable?) { onState("Connection lost") }
            override fun messageArrived(topic: String?, message: MqttMessage?) {
                message?.payload?.let { handle(it) }
            }
            override fun deliveryComplete(token: IMqttDeliveryToken?) = Unit
        })
        val opts = MqttConnectOptions().apply {
            isCleanSession = true
            isAutomaticReconnect = true
            connectionTimeout = 20
            keepAliveInterval = 60
            socketFactory = javax.net.ssl.SSLSocketFactory.getDefault()
        }
        c.connect(opts)
        c.subscribe(topic, 1)
        client = c
        sendHello()
        onState("ROOM ACTIVE • TLS")
    }

    fun disconnect() {
        try { client?.disconnect() } catch (_: Exception) {}
        try { client?.close() } catch (_: Exception) {}
        client = null
        peers.clear()
        onPeers(emptyList())
    }

    fun acceptPeer(n: String) {
        peers[n]?.verified = true
        sendControl("ACCEPT", n)
        onPeers(peers.values.toList())
    }

    fun rejectPeer(n: String) = sendControl("REJECT", n)

    fun blockPeer(n: String) {
        blocked += n
        if (peers.containsKey(n)) sendControl("BLOCK", n)
        peers.remove(n)
        onPeers(peers.values.toList())
    }

    fun ping(n: String) = sendControl("PING", n)

    fun sendChat(text: String, target: String? = null) {
        val targets = if (target != null) listOf(target)
        else peers.values.filter { it.verified && it.nick !in blocked }.map { it.nick }
        require(targets.isNotEmpty()) { "No accepted users" }
        for (t in targets) publish(signEncrypt(t, text.toByteArray(StandardCharsets.UTF_8), if (target == null) "MSG" else "PM"))
    }

    private fun sendHello() {
        val body = linkedMapOf<String, Any?>(
            "v" to 2,
            "type" to "HELLO",
            "nick" to nick,
            "xpub" to b64(crypto.xpub),
            "spub" to b64(crypto.epub),
            "ts" to System.currentTimeMillis(),
        )
        body["sig"] = b64(crypto.sign(canonical(body)))
        publish(body)
    }

    private fun sendControl(cmd: String, target: String = "") {
        if (room.isBlank()) return
        val obj = linkedMapOf<String, Any?>(
            "v" to 2,
            "type" to "CONTROL",
            "cmd" to cmd,
            "sender" to nick,
            "target" to target,
            "ts" to System.currentTimeMillis(),
            "id" to UUID.randomUUID().toString().replace("-", ""),
        )
        obj["sig"] = b64(crypto.sign(canonical(obj)))
        publish(obj)
    }

    private fun signEncrypt(target: String, plain: ByteArray, kind: String): MutableMap<String, Any?> {
        val p = peers[target] ?: error("Unknown peer")
        require(p.verified) { "Peer not accepted" }
        p.sendCounter++
        val epoch = System.currentTimeMillis() / 1000L / KEY_REFRESH_SECONDS
        val env = linkedMapOf<String, Any?>(
            "v" to 2,
            "type" to "DATA",
            "id" to UUID.randomUUID().toString().replace("-", ""),
            "kind" to kind,
            "sender" to nick,
            "target" to target,
            "counter" to p.sendCounter,
            "key_epoch" to epoch,
            "ts" to System.currentTimeMillis(),
        )
        val aad = canonical(env)
        val (nonce, ct) = crypto.encrypt(crypto.derive(p.xpub, room, epoch), plain, aad)
        env["nonce"] = b64(nonce)
        env["ct"] = b64(ct)
        env["sig"] = b64(crypto.sign(canonical(env)))
        return env
    }

    private fun publish(obj: Map<String, Any?>) {
        val raw = canonical(obj)
        val topic = TOPIC_PREFIX + "/" + sha256Hex(room.toByteArray()).take(32)
        val png = Stego.embed(raw)
        client?.publish(topic, MqttMessage(png).apply { qos = 1 })
    }

    private fun handle(raw: ByteArray) {
        try {
            val payload = Stego.extract(raw)
            val o = JSONObject(String(payload, StandardCharsets.UTF_8))
            if (o.optInt("v") != 2) return
            when (o.optString("type")) {
                "HELLO" -> handleHello(o)
                "CONTROL" -> handleControl(o)
                "DATA" -> handleData(o)
            }
        } catch (_: Exception) {}
    }

    private fun handleHello(o: JSONObject) {
        try {
            val n = o.getString("nick")
            if (n == nick || n in blocked) return
            val ts = o.getLong("ts")
            if (abs(System.currentTimeMillis() - ts) > MAX_AGE_MS) return
            val x = b64d(o.getString("xpub"))
            val s = b64d(o.getString("spub"))
            val sig = b64d(o.getString("sig"))
            val m = jsonToMap(o).toMutableMap().also { it.remove("sig") }
            if (!crypto.verify(s, canonical(m), sig)) return
            if (x.size != 32 || s.size != 32) return
            val fp = fingerprint(s)
            val prev = peers[n]
            if (prev != null && prev.fingerprint != fp) {
                onLine(ChatLine("SYSTEM", "Security warning: key changed for " + n, true))
                return
            }
            peers[n] = Peer(n, x, s, fp, prev?.verified == true)
            if (prev == null) onRequest(n, fp)
            onPeers(peers.values.toList())
        } catch (_: Exception) {}
    }

    private fun handleControl(o: JSONObject) {
        val sender = o.optString("sender")
        val p = peers[sender] ?: return
        if (sender in blocked) return
        try {
            val m = jsonToMap(o).toMutableMap()
            val sig = b64d(m.remove("sig") as String)
            if (!crypto.verify(p.spub, canonical(m), sig)) return
            if (abs(System.currentTimeMillis() - o.getLong("ts")) > MAX_AGE_MS) return
            when (o.optString("cmd")) {
                "ACCEPT" -> if (o.optString("target") == nick) {
                    p.verified = true
                    onState(sender + " accepted")
                }
                "REJECT" -> if (o.optString("target") == nick) {
                    p.verified = false
                    onState(sender + " declined")
                }
                "BLOCK" -> if (o.optString("target") == nick) {
                    p.verified = false
                    onState(sender + " blocked this session")
                }
                "PING" -> if (o.optString("target") == nick) onLine(ChatLine("SYSTEM", sender + " pinged you", true))
                "LEAVE" -> peers.remove(sender)
            }
            onPeers(peers.values.toList())
        } catch (_: Exception) {}
    }

    private fun handleData(o: JSONObject) {
        val sender = o.optString("sender")
        if (o.optString("target") != nick || sender in blocked) return
        val p = peers[sender] ?: return
        try {
            val id = o.getString("id")
            if (seen.contains(id)) return
            val ts = o.getLong("ts")
            if (abs(System.currentTimeMillis() - ts) > MAX_AGE_MS) return
            val counter = o.getLong("counter")
            if (counter <= p.recvCounter) return
            val epoch = o.getLong("key_epoch")
            val cur = System.currentTimeMillis() / 1000L / KEY_REFRESH_SECONDS
            if (abs(epoch - cur) > 1) return
            val all = jsonToMap(o).toMutableMap()
            val sig = b64d(all.remove("sig") as String)
            if (!crypto.verify(p.spub, canonical(all), sig)) return
            val aadMap = jsonToMap(o).toMutableMap().also {
                it.remove("nonce")
                it.remove("ct")
                it.remove("sig")
            }
            val plain = crypto.decrypt(
                crypto.derive(p.xpub, room, epoch),
                b64d(o.getString("nonce")),
                b64d(o.getString("ct")),
                canonical(aadMap),
            )
            seen.addLast(id)
            while (seen.size > 5000) seen.removeFirst()
            p.recvCounter = counter
            val txt = String(plain, StandardCharsets.UTF_8)
            when (o.optString("kind")) {
                "MSG" -> onLine(ChatLine(sender, txt, false))
                "PM" -> onLine(ChatLine("PRIVATE/" + sender, txt, false))
            }
        } catch (_: Exception) {}
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { CypherApp() }
    }
}

@Composable
fun CypherApp() {
    var dark by remember { mutableStateOf(true) }
    var logged by remember { mutableStateOf(false) }
    var nick by remember { mutableStateOf("") }
    var room by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Offline") }
    var peers by remember { mutableStateOf(listOf<Peer>()) }
    var lines by remember { mutableStateOf(listOf<ChatLine>()) }
    var request by remember { mutableStateOf<Pair<String, String>?>(null) }
    var settings by remember { mutableStateOf(false) }
    var pingEnabled by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    val node = remember {
        CypherNode(
            onLine = { l -> scope.launch { lines = lines + l } },
            onPeers = { p -> scope.launch { peers = p } },
            onRequest = { n, f -> scope.launch { request = n to f } },
            onState = { s -> scope.launch { status = s } },
        )
    }
    val bg = if (dark) Color(0xFF0B0E12) else Color(0xFFF5F6F8)
    val fg = if (dark) Color(0xFFE7EDF2) else Color(0xFF111318)
    val accent = if (dark) Color(0xFF4CCF82) else Color(0xFF111318)

    MaterialTheme(colorScheme = if (dark) darkColorScheme(primary = accent) else lightColorScheme(primary = accent)) {
        Surface(Modifier.fillMaxSize(), color = bg) {
            if (!logged) {
                Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.Center) {
                    Text("CYPHER_NET", color = accent, style = MaterialTheme.typography.headlineLarge)
                    Spacer(Modifier.height(16.dp))
                    OutlinedTextField(nick, { nick = it }, label = { Text("Anonymous nickname") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { if (nick.isNotBlank()) logged = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("APPROVE & START SESSION")
                    }
                }
            } else {
                Column(Modifier.fillMaxSize().padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("CYPHER_NET", color = accent, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                        IconButton(onClick = { settings = true }) { Icon(Icons.Default.Settings, null, tint = fg) }
                    }
                    Text(status, color = if (status.contains("ACTIVE")) Color(0xFF4CCF82) else fg)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(room, { room = it }, label = { Text("Room code") }, singleLine = true, modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = {
                            if (room.length >= 8) scope.launch(Dispatchers.IO) {
                                try {
                                    node.connect(room, nick)
                                } catch (e: Exception) {
                                    withContext(Dispatchers.Main) {
                                        status = if (e is MqttException) {
                                            "MQTT error " + e.reasonCode + ": " + (e.cause?.message ?: e.message ?: "unknown")
                                        } else {
                                            "Connection error: " + (e.message ?: e.javaClass.simpleName)
                                        }
                                    }
                                }
                            }
                        }) { Text("C") }
                    }
                    Spacer(Modifier.height(8.dp))
                    AnimatedVisibility(peers.isNotEmpty()) {
                        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 120.dp)) {
                            items(peers) { p ->
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text((if (p.verified) "✓ " else "? ") + p.nick, modifier = Modifier.weight(1f), color = fg)
                                    if (pingEnabled) TextButton(onClick = { node.ping(p.nick) }) { Text("PING") }
                                    TextButton(onClick = { node.blockPeer(p.nick) }) { Text("BLOCK") }
                                }
                            }
                        }
                    }
                    LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                        items(lines) { l ->
                            Text("[" + l.sender + "] " + l.text, color = if (l.system) Color(0xFF4AAAC4) else fg, modifier = Modifier.padding(vertical = 4.dp))
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(msg, { msg = it }, placeholder = { Text("Encrypted message") }, modifier = Modifier.weight(1f), singleLine = true)
                        IconButton(onClick = {
                            if (msg.isNotBlank()) {
                                try {
                                    node.sendChat(msg)
                                    lines = lines + ChatLine(nick, msg)
                                    msg = ""
                                } catch (e: Exception) {
                                    status = e.message ?: "Send failed"
                                }
                            }
                        }) { Icon(Icons.Default.Send, null, tint = accent) }
                    }
                }
            }
        }

        request?.let { pair ->
            val n = pair.first
            val f = pair.second
            AlertDialog(
                onDismissRequest = {},
                title = { Text("Chat request") },
                text = { Text(n + " joined.\nFingerprint: " + f) },
                confirmButton = { TextButton(onClick = { node.acceptPeer(n); request = null }) { Text("Accept") } },
                dismissButton = {
                    Row {
                        TextButton(onClick = { node.rejectPeer(n); request = null }) { Text("Decline") }
                        TextButton(onClick = { node.blockPeer(n); request = null }) { Text("Block") }
                    }
                },
            )
        }

        if (settings) {
            AlertDialog(
                onDismissRequest = { settings = false },
                title = { Text("Settings") },
                text = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Dark theme", modifier = Modifier.weight(1f))
                            Switch(dark, { dark = it })
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Ping enabled", modifier = Modifier.weight(1f))
                            Switch(pingEnabled, { pingEnabled = it })
                        }
                        Text("Direct TLS is active. Optional Android Tor/Orbot routing will be added after the base APK is verified.", style = MaterialTheme.typography.bodySmall)
                    }
                },
                confirmButton = { TextButton(onClick = { settings = false }) { Text("Close") } },
            )
        }
    }
}

fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)
fun sha256Hex(data: ByteArray): String = sha256(data).joinToString("") { "%02x".format(it) }
fun b64(data: ByteArray): String = Base64.getEncoder().encodeToString(data)
fun b64d(s: String): ByteArray = Base64.getDecoder().decode(s)
fun fingerprint(pub: ByteArray): String = sha256Hex(pub).chunked(4).take(5).joinToString(":")

fun canonical(map: Map<String, Any?>): ByteArray {
    val keys = map.keys.sorted()
    val s = buildString {
        append("{")
        keys.forEachIndexed { i, k ->
            if (i > 0) append(",")
            append(JSONObject.quote(k))
            append(":")
            val v = map[k]
            append(
                when (v) {
                    null -> "null"
                    is String -> JSONObject.quote(v)
                    is Number, is Boolean -> v.toString()
                    else -> JSONObject.quote(v.toString())
                }
            )
        }
        append("}")
    }
    return s.toByteArray(StandardCharsets.UTF_8)
}

fun jsonToMap(o: JSONObject): Map<String, Any?> {
    val m = linkedMapOf<String, Any?>()
    val it = o.keys()
    while (it.hasNext()) {
        val k = it.next()
        m[k] = o.get(k)
    }
    return m
}
