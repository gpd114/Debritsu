package com.debritsu.app.cast

import android.content.Context
import android.net.wifi.WifiManager
import com.debritsu.app.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Just enough DLNA to push a URL at a television.
 *
 * Samsung, LG and Sony sets don't speak Google Cast but almost all of them
 * implement UPnP AVTransport. Since debrid links are plain HTTPS, "casting" is
 * really just handing the TV a URL and telling it to play — no transcoding, no
 * local server, and the phone isn't in the data path at all.
 *
 * Cling, the library everyone used for this, is discontinued; the protocol is
 * small enough to speak directly.
 */
object Dlna {

    data class Renderer(
        val name: String,
        val controlUrl: String,
        val address: String
    )

    private const val SSDP_ADDRESS = "239.255.255.250"
    private const val SSDP_PORT = 1900

    /**
     * The shared client is tuned for debrid downloads — 30s to connect, 150s
     * overall. Applied to a television that answered SSDP and then went quiet,
     * that stalls discovery for half a minute. These are LAN calls; they should
     * fail fast. Built from the shared client so the connection and thread
     * pools are still reused.
     */
    private val local by lazy {
        Http.client.newBuilder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    private val SEARCH = (
        "M-SEARCH * HTTP/1.1\r\n" +
            "HOST: $SSDP_ADDRESS:$SSDP_PORT\r\n" +
            "MAN: \"ssdp:discover\"\r\n" +
            "MX: 3\r\n" +
            "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n"
        ).toByteArray()

    /**
     * Broadcasts an SSDP search and resolves whatever answers.
     *
     * A multicast lock is required or Android filters the replies out on most
     * devices — the single most common reason home-grown DLNA discovery finds
     * nothing.
     */
    suspend fun discover(context: Context, timeoutMs: Int = 4000): List<Renderer> =
        withContext(Dispatchers.IO) {
            val wifi = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as WifiManager
            val lock = wifi.createMulticastLock("debritsu-dlna").apply {
                setReferenceCounted(true)
                acquire()
            }

            val locations = linkedSetOf<String>()
            try {
                DatagramSocket().use { socket ->
                    socket.soTimeout = 900
                    // SSDP is UDP with no retransmission, so a single search can
                    // simply go missing. Asking three times costs nothing and
                    // makes discovery far more consistent.
                    val target = InetSocketAddress(
                        InetAddress.getByName(SSDP_ADDRESS), SSDP_PORT
                    )
                    repeat(3) {
                        runCatching {
                            socket.send(DatagramPacket(SEARCH, SEARCH.size, target))
                        }
                    }

                    val deadline = System.currentTimeMillis() + timeoutMs
                    val buffer = ByteArray(2048)
                    while (System.currentTimeMillis() < deadline) {
                        val packet = DatagramPacket(buffer, buffer.size)
                        val ok = runCatching { socket.receive(packet); true }.getOrDefault(false)
                        if (!ok) continue
                        val text = String(packet.data, 0, packet.length)
                        // Header names are case-insensitive and devices really
                        // do disagree: LG sends "Location:", others "LOCATION:".
                        // Match and strip the same way, or the header name ends
                        // up inside the URL and the device is dropped silently.
                        val location = text.lineSequence()
                            .firstOrNull { it.trim().startsWith("LOCATION:", ignoreCase = true) }
                            ?.trim()?.substringAfter(':')?.trim()
                        if (!location.isNullOrEmpty()) locations += location
                    }
                }
            } finally {
                runCatching { lock.release() }
            }

            // One slow or dead responder shouldn't hold up the rest, so every
            // description is fetched at the same time.
            coroutineScope {
                locations.map { async { describe(it) } }.awaitAll()
            }.filterNotNull().distinctBy { it.controlUrl }
        }

    /** Fetches the device description and digs out the AVTransport control URL. */
    private fun describe(location: String): Renderer? = runCatching {
        val body = local.newCall(Request.Builder().url(location).build())
            .execute().use { it.body?.string().orEmpty() }

        val name = Regex("<friendlyName>(.*?)</friendlyName>", RegexOption.DOT_MATCHES_ALL)
            .find(body)?.groupValues?.get(1)?.trim() ?: "Unknown device"

        // Find the AVTransport service block, then its controlURL.
        val service = Regex(
            "<service>(?:(?!</service>).)*?AVTransport(?:(?!</service>).)*?</service>",
            RegexOption.DOT_MATCHES_ALL
        ).find(body)?.value ?: return@runCatching null

        val control = Regex("<controlURL>(.*?)</controlURL>", RegexOption.DOT_MATCHES_ALL)
            .find(service)?.groupValues?.get(1)?.trim() ?: return@runCatching null

        val base = URL(location)
        // getPort() is -1 when the LOCATION header leaves the port implicit,
        // which would otherwise build "http://192.168.1.5:-1/control".
        val port = base.port.takeIf { it != -1 } ?: base.defaultPort
        val origin = "${base.protocol}://${base.host}:$port"
        val controlUrl = when {
            control.startsWith("http") -> control
            control.startsWith("/") -> "$origin$control"
            else -> "$origin/$control"
        }
        Renderer(name, controlUrl, base.host)
    }.getOrNull()

    private fun soap(controlUrl: String, action: String, inner: String): Boolean = runCatching {
        val envelope = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
<s:Body><u:$action xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">$inner</u:$action></s:Body>
</s:Envelope>"""

        val req = Request.Builder()
            .url(controlUrl)
            .header("SOAPACTION", "\"urn:schemas-upnp-org:service:AVTransport:1#$action\"")
            .post(envelope.toRequestBody("text/xml; charset=\"utf-8\"".toMediaType()))
            .build()
        local.newCall(req).execute().use { it.isSuccessful }
    }.getOrDefault(false)

    private fun xmlEscape(s: String) = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;")

    /**
     * Hands the renderer a URL and starts it. Metadata is deliberately minimal:
     * some TVs reject anything they can't parse, and an empty DIDL is the most
     * widely accepted option.
     */
    suspend fun play(
        renderer: Renderer,
        url: String,
        title: String
    ): Boolean = withContext(Dispatchers.IO) {
        val didl = xmlEscape(
            """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" """ +
                """xmlns:dc="http://purl.org/dc/elements/1.1/" """ +
                """xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">""" +
                """<item id="0" parentID="-1" restricted="1">""" +
                """<dc:title>${xmlEscape(title)}</dc:title>""" +
                """<upnp:class>object.item.videoItem</upnp:class>""" +
                """<res protocolInfo="http-get:*:video/mp4:*">${xmlEscape(url)}</res>""" +
                """</item></DIDL-Lite>"""
        )

        // A renderer left mid-playback rejects SetAVTransportURI outright — LG
        // answers 500 to every attempt until it is stopped — so anything after
        // a failed cast fails too, including the retry. Clearing the transport
        // first makes each attempt independent of the last.
        soap(renderer.controlUrl, "Stop", "<InstanceID>0</InstanceID>")

        val set = soap(
            renderer.controlUrl, "SetAVTransportURI",
            "<InstanceID>0</InstanceID>" +
                "<CurrentURI>${xmlEscape(url)}</CurrentURI>" +
                "<CurrentURIMetaData>$didl</CurrentURIMetaData>"
        )
        if (!set) return@withContext false

        soap(renderer.controlUrl, "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>")
    }

    /** Seconds → the HH:MM:SS form UPnP expects. */
    private fun timecode(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return "%02d:%02d:%02d".format(h, m, s)
    }

    suspend fun seek(renderer: Renderer, positionSeconds: Long) = withContext(Dispatchers.IO) {
        soap(
            renderer.controlUrl, "Seek",
            "<InstanceID>0</InstanceID><Unit>REL_TIME</Unit>" +
                "<Target>${timecode(positionSeconds)}</Target>"
        )
    }
}
