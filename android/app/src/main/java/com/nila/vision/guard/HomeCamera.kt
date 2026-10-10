package com.nila.vision.guard

import android.content.Context
import android.net.Uri
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Calendar
import java.util.TimeZone
import java.util.UUID

/**
 * The family's own camera on the home Wi-Fi ("Home" on the Watch screen). Ported from the CP Plus CP-E41Q
 * experiment (babytest/experiments/cpplus-cctv), where these steps were worked out against the real camera:
 *
 *  1. [discover]: WS-Discovery on the Wi-Fi finds ONVIF cameras (the CP-E41Q answers once ONVIF is switched on
 *     in ezyKam+: camera → Settings → Onvif).
 *  2. [findStream]: ONVIF asks the camera for its RTSP addresses and picks the small sub stream (CP-E41Q:
 *     640x360 H.264 on port 5543, which the phone decodes fastest; the main stream is really H.265 2560x1440).
 *     The password is only sent as a WS-Security digest, never in clear, and is not kept: CP Plus puts a
 *     32-hex key in the stream path instead, which works without a login.
 *  3. [isOnline]: one RTSP OPTIONS to the camera every few seconds; any RTSP answer means it is on the Wi-Fi.
 *
 * Plain sockets rather than an HTTP client: ONVIF is HTTP without TLS on the local network, which the app's
 * network policy rightly refuses for its HTTP clients; RTSP goes over its own sockets anyway.
 */
object HomeCamera {

    /** A camera that answered on the Wi-Fi. */
    data class Found(val host: String, val onvifUrl: String, val name: String)

    /** One of the camera's streams. */
    data class Stream(val url: String, val width: Int, val height: Int, val encoding: String)

    class SetupError(message: String) : Exception(message)

    private const val NS_DEV = "http://www.onvif.org/ver10/device/wsdl"
    private const val NS_MEDIA = "http://www.onvif.org/ver10/media/wsdl"

    /** The camera's address on the Wi-Fi, from its stream URL (for the screen). */
    fun host(url: String): String? = runCatching { Uri.parse(url).host }.getOrNull()?.takeIf { it.isNotBlank() }

    /** Is the camera answering on the Wi-Fi? One RTSP OPTIONS, without the stream path (it holds the key). */
    suspend fun isOnline(url: String, timeoutMs: Int = 1_500): Boolean = withContext(Dispatchers.IO) {
        val u = runCatching { Uri.parse(url) }.getOrNull() ?: return@withContext false
        val host = u.host ?: return@withContext false
        val port = if (u.port > 0) u.port else 554
        runCatching {
            Socket().use { s ->
                s.connect(InetSocketAddress(host, port), timeoutMs)
                s.soTimeout = timeoutMs
                s.getOutputStream().write("OPTIONS rtsp://$host:$port/ RTSP/1.0\r\nCSeq: 1\r\nUser-Agent: Nila\r\n\r\n".toByteArray())
                s.getInputStream().bufferedReader().readLine()?.startsWith("RTSP/") == true
            }
        }.getOrDefault(false)
    }

    /**
     * The camera stopped answering at its saved address: it may have come back with a new one (the router or
     * hotspot handed out another IP after a restart). Looks for cameras on the Wi-Fi and returns the saved
     * address moved to the camera whose stream answers with the same path (and key), or null.
     */
    suspend fun relocate(context: Context, url: String): String? {
        val old = host(url) ?: return null
        for (f in discover(context)) {
            if (f.host == old) continue
            val moved = url.replaceFirst("@$old", "@${f.host}").replaceFirst("//$old", "//${f.host}")
            if (moved != url && streamAnswers(moved)) return moved
        }
        return null
    }

    /** RTSP DESCRIBE on the full address answers 200 (or asks for the login it already carries): the stream is there. */
    private suspend fun streamAnswers(url: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val u = URI(url)
            val port = if (u.port > 0) u.port else 554
            Socket().use { s ->
                s.connect(InetSocketAddress(u.host, port), 2_000)
                s.soTimeout = 3_000
                val bare = url.replace(Regex("//[^/@]*@"), "//")
                s.getOutputStream().write("DESCRIBE $bare RTSP/1.0\r\nCSeq: 2\r\nAccept: application/sdp\r\nUser-Agent: Nila\r\n\r\n".toByteArray())
                val line = s.getInputStream().bufferedReader().readLine() ?: ""
                line.contains(" 200") || (line.contains(" 401") && url.contains('@'))
            }
        }.getOrDefault(false)
    }

    /** ONVIF cameras on the Wi-Fi (WS-Discovery, multicast UDP 3702). Empty if none answered in [waitMs]. */
    suspend fun discover(context: Context, waitMs: Long = 3_000): List<Found> = withContext(Dispatchers.IO) {
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        // Android drops incoming multicast to save battery unless an app holds this lock (the answers are unicast,
        // but some cameras answer to the group).
        val lock = wifi?.createMulticastLock("nila:onvif")?.apply { setReferenceCounted(false); acquire() }
        try {
            MulticastSocket().use { sock ->
                wifiInterface()?.let { runCatching { sock.networkInterface = it } }
                sock.soTimeout = 400
                val group = InetAddress.getByName("239.255.255.250")
                val probe = probeMessage().toByteArray()
                repeat(2) { sock.send(DatagramPacket(probe, probe.size, group, 3702)) }
                val found = linkedMapOf<String, Found>()
                val buf = ByteArray(65_535)
                val end = System.currentTimeMillis() + waitMs
                while (System.currentTimeMillis() < end) {
                    val pkt = DatagramPacket(buf, buf.size)
                    try {
                        sock.receive(pkt)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    val xml = String(pkt.data, 0, pkt.length)
                    val xaddrs = Regex("<(?:\\w+:)?XAddrs>([^<]+)<").find(xml)?.groupValues?.get(1) ?: continue
                    val onvif = xaddrs.trim().split(Regex("\\s+")).firstOrNull { it.startsWith("http://") } ?: continue
                    val host = pkt.address.hostAddress ?: continue
                    val name = Regex("onvif://www\\.onvif\\.org/name/([^\\s<]+)").find(xml)?.groupValues?.get(1)
                        ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it).replace('_', ' ') }
                        ?: "Camera"
                    found.putIfAbsent(host, Found(host, onvif, name))
                }
                found.values.toList()
            }
        } finally {
            lock?.release()
        }
    }

    /**
     * Asks the camera (ONVIF on [host]:[port], or the address [discover] reported) for its streams and returns
     * the one to watch: the smallest H.264 stream, as in the experiment. Throws [SetupError] with a sentence a
     * parent can act on.
     */
    suspend fun findStream(onvifUrl: String, user: String, password: String): Stream = withContext(Dispatchers.IO) {
        val camHost = runCatching { URI(onvifUrl).host }.getOrNull() ?: throw SetupError("That camera address is not valid.")
        // 1. The camera's clock: the login digest must use it (a few seconds apart and the camera refuses).
        val time = try {
            post(onvifUrl, NS_DEV, "GetSystemDateAndTime", "<GetSystemDateAndTime xmlns=\"$NS_DEV\"/>", null)
        } catch (e: Exception) {
            throw SetupError("No answer from $camHost. Check that the phone is on the camera's Wi-Fi and ONVIF is " +
                                 "switched on in the camera's app.")
        }
        val auth = Auth(user, password, clockOffset(time.second))
        // 2. Where the media service is.
        val caps = post(onvifUrl, NS_DEV, "GetCapabilities",
                        "<GetCapabilities xmlns=\"$NS_DEV\"><Category>All</Category></GetCapabilities>", auth)
        if (caps.first == 401 || isAuthFault(caps.second)) {
            throw SetupError("The camera did not accept that user name and password (the ONVIF ones from the camera's app).")
        }
        val media = Regex("<(?:\\w+:)?Media>\\s*<(?:\\w+:)?XAddr>([^<]+)<").find(caps.second)?.groupValues?.get(1)?.trim()
            ?: onvifUrl
        // 3. Profiles, and 4. the RTSP address of each.
        val prof = post(media, NS_MEDIA, "GetProfiles", "<GetProfiles xmlns=\"$NS_MEDIA\"/>", auth)
        if (prof.first == 401 || isAuthFault(prof.second)) {
            throw SetupError("The camera did not accept that user name and password (the ONVIF ones from the camera's app).")
        }
        val streams = Regex("<(?:\\w+:)?Profiles[^>]*token=\"([^\"]+)\"(.*?)</(?:\\w+:)?Profiles>", RegexOption.DOT_MATCHES_ALL)
            .findAll(prof.second)
            .mapNotNull { m ->
                val token = m.groupValues[1]
                val body = m.groupValues[2]
                val uri = post(media, NS_MEDIA, "GetStreamUri",
                               "<GetStreamUri xmlns=\"$NS_MEDIA\"><StreamSetup><tt:Stream>RTP-Unicast</tt:Stream>" +
                                   "<tt:Transport><tt:Protocol>RTSP</tt:Protocol></tt:Transport></StreamSetup>" +
                                   "<ProfileToken>$token</ProfileToken></GetStreamUri>", auth)
                val url = tag(uri.second, "Uri")?.replace("&amp;", "&") ?: return@mapNotNull null
                Stream(fixHost(url, camHost), tag(body, "Width")?.toIntOrNull() ?: 0,
                       tag(body, "Height")?.toIntOrNull() ?: 0, tag(body, "Encoding") ?: "?")
            }
            .toList()
        if (streams.isEmpty()) throw SetupError("The camera answered but did not give a video stream address.")
        val pick = streams.filter { it.encoding.equals("H264", true) && it.width > 0 }.minByOrNull { it.width }
            ?: streams.filter { it.width > 0 }.minByOrNull { it.width }
            ?: streams.first()
        // Most cameras want the login on the stream too; CP Plus's key path does not (and the password then stays
        // out of the saved address).
        val url = if (needsLogin(pick.url)) withLogin(pick.url, user, password) else pick.url
        pick.copy(url = url)
    }

    /** An ONVIF address for a camera typed in by hand: port 8000 (CP Plus) unless the parent gave one. */
    fun onvifUrlFor(hostInput: String): String {
        val h = hostInput.trim().removePrefix("http://").trimEnd('/')
        return if (h.contains(':')) "http://$h/onvif/device_service" else "http://$h:8000/onvif/device_service"
    }

    // ---------------------------------------------------------------- ONVIF plumbing

    private class Auth(val user: String, val password: String, val clockOffsetMs: Long) {
        fun header(): String {
            val created = utcStamp(System.currentTimeMillis() + clockOffsetMs)
            val nonce = ByteArray(16).also { SecureRandom().nextBytes(it) }
            val sha = MessageDigest.getInstance("SHA-1")
            sha.update(nonce)
            sha.update(created.toByteArray())
            sha.update(password.toByteArray())
            val digest = Base64.getEncoder().encodeToString(sha.digest())
            return "<s:Header><Security s:mustUnderstand=\"1\" xmlns=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd\">" +
                "<UsernameToken><Username>${xmlEscape(user)}</Username>" +
                "<Password Type=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#PasswordDigest\">$digest</Password>" +
                "<Nonce EncodingType=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-soap-message-security-1.0#Base64Binary\">" +
                "${Base64.getEncoder().encodeToString(nonce)}</Nonce>" +
                "<Created xmlns=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd\">$created</Created>" +
                "</UsernameToken></Security></s:Header>"
        }
    }

    /** One SOAP call over a plain socket. Returns the HTTP status and the body. */
    private fun post(url: String, ns: String, action: String, body: String, auth: Auth?): Pair<Int, String> {
        val u = URI(url)
        val host = u.host ?: throw SetupError("Bad camera address")
        val port = if (u.port > 0) u.port else 80
        val path = (u.rawPath ?: "").ifEmpty { "/" } + (u.rawQuery?.let { "?$it" } ?: "")
        val env = ("<?xml version=\"1.0\" encoding=\"UTF-8\"?><s:Envelope xmlns:s=\"http://www.w3.org/2003/05/soap-envelope\" " +
            "xmlns:tt=\"http://www.onvif.org/ver10/schema\">${auth?.header() ?: ""}<s:Body>$body</s:Body></s:Envelope>").toByteArray()
        Socket().use { s ->
            s.connect(InetSocketAddress(host, port), 4_000)
            s.soTimeout = 8_000
            val head = "POST $path HTTP/1.1\r\nHost: $host:$port\r\n" +
                "Content-Type: application/soap+xml; charset=utf-8; action=\"$ns/$action\"\r\n" +
                "Content-Length: ${env.size}\r\nConnection: close\r\n\r\n"
            s.getOutputStream().apply { write(head.toByteArray()); write(env); flush() }
            val raw = s.getInputStream().readBytes()
            val split = indexOf(raw, "\r\n\r\n".toByteArray())
            if (split < 0) return 0 to String(raw)
            val headers = String(raw, 0, split)
            val code = headers.lineSequence().firstOrNull()?.split(' ')?.getOrNull(1)?.toIntOrNull() ?: 0
            var payload = raw.copyOfRange(split + 4, raw.size)
            if (headers.contains("chunked", ignoreCase = true)) payload = dechunk(payload)
            return code to String(payload)
        }
    }

    private fun isAuthFault(xml: String) =
        xml.contains("Fault") && (xml.contains("NotAuthorized", true) || xml.contains("FailedAuthentication", true) ||
            xml.contains("Sender", true))

    private fun clockOffset(xml: String): Long {
        val m = Regex("UTCDateTime>.*?Year>(\\d+)<.*?Month>(\\d+)<.*?Day>(\\d+)<.*?Hour>(\\d+)<.*?Minute>(\\d+)<.*?Second>(\\d+)<",
                      RegexOption.DOT_MATCHES_ALL).find(xml) ?: return 0L
        val (y, mo, d, h, mi, se) = m.destructured
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(y.toInt(), mo.toInt() - 1, d.toInt(), h.toInt(), mi.toInt(), se.toInt())
        }
        return cal.timeInMillis - System.currentTimeMillis()
    }

    private fun utcStamp(ms: Long): String {
        val c = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = ms }
        return "%04d-%02d-%02dT%02d:%02d:%02dZ".format(
            c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH),
            c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), c.get(Calendar.SECOND))
    }

    private fun tag(xml: String, name: String) =
        Regex("<(?:\\w+:)?$name[^>]*>([^<]*)<").find(xml)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }

    /** Some cameras report 0.0.0.0 or their internal name as the stream host: use the address that answered. */
    private fun fixHost(url: String, camHost: String): String {
        val u = runCatching { URI(url) }.getOrNull() ?: return url
        val h = u.host ?: return url
        return if (h == "0.0.0.0" || h == "localhost" || h == "127.0.0.1") url.replaceFirst(h, camHost) else url
    }

    /** RTSP DESCRIBE without a login: does the stream answer 401? */
    private fun needsLogin(url: String): Boolean = runCatching {
        val u = URI(url)
        val port = if (u.port > 0) u.port else 554
        val bare = url.replace(Regex("//[^/@]*@"), "//")
        Socket().use { s ->
            s.connect(InetSocketAddress(u.host, port), 3_000)
            s.soTimeout = 4_000
            s.getOutputStream().write("DESCRIBE $bare RTSP/1.0\r\nCSeq: 2\r\nAccept: application/sdp\r\nUser-Agent: Nila\r\n\r\n".toByteArray())
            s.getInputStream().bufferedReader().readLine()?.contains(" 401") == true
        }
    }.getOrDefault(false)

    private fun withLogin(url: String, user: String, password: String): String {
        if (user.isBlank()) return url
        val enc = { v: String -> URLEncoder.encode(v, "UTF-8").replace("+", "%20") }
        return url.replaceFirst("://", "://${enc(user)}:${enc(password)}@")
    }

    private fun wifiInterface(): NetworkInterface? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().firstOrNull { it.isUp && !it.isLoopback && it.name.startsWith("wlan") }
    }.getOrNull()

    private fun probeMessage() =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
            "<e:Envelope xmlns:e=\"http://www.w3.org/2003/05/soap-envelope\" xmlns:w=\"http://schemas.xmlsoap.org/ws/2004/08/addressing\" " +
            "xmlns:d=\"http://schemas.xmlsoap.org/ws/2005/04/discovery\" xmlns:dn=\"http://www.onvif.org/ver10/network/wsdl\">" +
            "<e:Header><w:MessageID>uuid:${UUID.randomUUID()}</w:MessageID>" +
            "<w:To e:mustUnderstand=\"true\">urn:schemas-xmlsoap-org:ws:2005:04:discovery</w:To>" +
            "<w:Action e:mustUnderstand=\"true\">http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</w:Action></e:Header>" +
            "<e:Body><d:Probe><d:Types>dn:NetworkVideoTransmitter</d:Types></d:Probe></e:Body></e:Envelope>"

    private fun xmlEscape(v: String) = v.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun indexOf(a: ByteArray, b: ByteArray): Int {
        outer@ for (i in 0..a.size - b.size) {
            for (j in b.indices) if (a[i + j] != b[j]) continue@outer
            return i
        }
        return -1
    }

    private fun dechunk(b: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        var i = 0
        while (i < b.size) {
            val eol = indexOf(b.copyOfRange(i, b.size), "\r\n".toByteArray()).takeIf { it >= 0 } ?: break
            val size = String(b, i, eol).trim().substringBefore(';').toIntOrNull(16) ?: break
            if (size == 0) break
            val start = i + eol + 2
            out.write(b, start, minOf(size, b.size - start))
            i = start + size + 2
        }
        return out.toByteArray()
    }
}

/** A camera address safe for logs and the screen: the password and CP Plus's 32-hex stream key are hidden. */
fun redactUrl(url: String): String =
    url.replace(Regex("//([^/@:]+):([^@/]*)@"), "//$1:***@")
        .replace(Regex("/[0-9a-fA-F]{24,}/"), "/***/")
