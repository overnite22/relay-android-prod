package io.github.overnite22.relay.kit

import io.github.overnite22.relay.kit.guac.RelayTunnelTransport
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelTest {
    @Test fun webSocketCarriesTokenAndRelaysText() {
        val server = MockWebServer()
        val echoed = CountDownLatch(1)
        val fromClient = Collections.synchronizedList(ArrayList<String>())
        server.enqueue(
            MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send("0.,1.t;")
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    fromClient.add(text)
                    webSocket.close(1000, null)
                }
            }).addHeader("Sec-WebSocket-Protocol", "guacamole").build(),
        )
        server.start()
        try {
            val url = server.url("/api/tunnel").toString().replace("http://", "ws://") + "?token=abc"
            val tunnel = RelayTunnelTransport(url, "rlt_device")
            val received = Collections.synchronizedList(ArrayList<String>())
            val closed = CountDownLatch(1)
            tunnel.onText = { received.add(it); tunnel.send("3.nop;"); echoed.countDown() }
            tunnel.onClose = { closed.countDown() }
            tunnel.start()
            assertTrue(echoed.await(5, TimeUnit.SECONDS))
            assertTrue(closed.await(5, TimeUnit.SECONDS))
            assertEquals(listOf("0.,1.t;"), received.toList())
            assertEquals(listOf("3.nop;"), fromClient.toList())
            val request = server.takeRequest()
            assertEquals("Bearer rlt_device", request.headers["Authorization"])
            assertEquals("1", request.headers["X-Relay"])
            assertEquals("guacamole", request.headers["Sec-WebSocket-Protocol"])
            assertEquals("/api/tunnel?token=abc", request.target)
        } finally {
            server.close()
        }
    }
}
