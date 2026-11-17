package io.github.overnite22.relay.kit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PairingTest {
    private val token = "rlt_" + "a".repeat(43)

    @Test fun parsesQrCode() {
        val a = Pairing.parse("relay://pair?server=https%3A%2F%2Frelay.example.com&token=$token")
        assertEquals("https://relay.example.com", a.server)
        assertEquals(token, a.token)
        assertEquals("relay.example.com", a.serverDisplay)
    }

    @Test fun rejectsForeignCodes() {
        assertEquals(PairingError.NotPairingCode, assertThrows(PairingError::class.java) { Pairing.parse("https://example.com/x") })
        assertEquals(PairingError.InvalidToken, assertThrows(PairingError::class.java) { Pairing.parse("relay://pair?server=https://a.example&token=abc") })
        assertEquals(PairingError.InsecureServer, assertThrows(PairingError::class.java) { Pairing.parse("relay://pair?server=http://a.example&token=$token") })
    }

    @Test fun manualServerNormalisation() {
        assertEquals("https://relay.example.com", Pairing.serverUrl("relay.example.com"))
        assertEquals("https://relay.example.com", Pairing.serverUrl("https://relay.example.com/some/path?x=1"))
        assertEquals("http://localhost:8080", Pairing.serverUrl("http://localhost:8080"))
        assertEquals("https://relay.example.com:8443", Pairing.serverUrl("  HTTPS://relay.example.com:8443/  "))
        assertThrows(PairingError::class.java) { Pairing.serverUrl("http://relay.example.com") }
        assertThrows(PairingError::class.java) { Pairing.serverUrl("https://user:pw@relay.example.com") }
        assertThrows(PairingError::class.java) { Pairing.serverUrl("") }
        assertThrows(PairingError::class.java) { Pairing.serverUrl("ftp://relay.example.com") }
        assertThrows(PairingError::class.java) { Pairing.account("https://a.example", "rlt_short") }
    }
}
