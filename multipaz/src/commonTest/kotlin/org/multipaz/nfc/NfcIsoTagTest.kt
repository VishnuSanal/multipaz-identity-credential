package org.multipaz.nfc

import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import org.multipaz.util.putUInt16
import kotlin.test.Test
import kotlin.test.assertEquals

class NfcIsoTagTest {
    @Test
    fun ndefReadMessageSplitsReadsToFitTransceiveLength() = runTest {
        val expectedMessage = NdefMessage(
            listOf(NdefRecord(NdefRecord.Tnf.UNKNOWN, payload = ByteString(ByteArray(32) { it.toByte() })))
        )
        val encodedMessage = expectedMessage.encode()
        val ndefFile = ByteArray(encodedMessage.size + 2)
        ndefFile.putUInt16(0, encodedMessage.size.toUInt())
        encodedMessage.copyInto(ndefFile, destinationOffset = 2)
        val tag = object : NfcIsoTag() {
            val requestedReadLengths = mutableListOf<Int>()

            override val maxTransceiveLength: Int
                get() = 12

            override suspend fun transceive(command: CommandApdu): ResponseApdu {
                assertEquals(Nfc.INS_READ_BINARY, command.ins)
                val offset = command.p1 * 0x100 + command.p2
                requestedReadLengths.add(command.le)
                return ResponseApdu(
                    Nfc.RESPONSE_STATUS_SUCCESS,
                    ByteString(ndefFile.copyOfRange(offset, offset + command.le))
                )
            }

            override suspend fun close() {}

            override suspend fun updateDialogMessage(message: String) {}
        }

        assertEquals(expectedMessage, tag.ndefReadMessage())
        assertEquals(listOf(2, 10, 10, 10, encodedMessage.size - 30), tag.requestedReadLengths)
    }
}
