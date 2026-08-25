package io.github.urionsisdi.nfcintime.crypto

import org.junit.Assert.assertEquals
import org.junit.Test

class TransfersTest {

    /**
     * The literal is the one in `web/server/internal/api/protocol_test.go`. If
     * this test and that one ever disagree, every transfer in flight is void.
     */
    @Test
    fun `the signed message is the server's message`() {
        val message = transferMessage(
            Transfer(
                nonce = "8f2c",
                from = "42",
                to = "43",
                amount = 3600,
                prevHash = "deadbeef",
                signedAt = 1787270512,
            ),
        )
        assertEquals(
            "nfcit/transfer/v1\n8f2c\n42\n43\n3600\n1787270512\ndeadbeef",
            message.toString(Charsets.UTF_8),
        )
    }
}
