package org.multipaz.utopia.knowntypes

import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.encodeToByteString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import org.multipaz.cbor.toDataItem
import org.multipaz.presentment.TransactionData
import org.multipaz.presentment.TransactionProtocol
import org.multipaz.util.toBase64Url

class TextAcceptanceTransactionTest {
    private val payload = TextAcceptanceTransaction.Payload(
        text = "I accept the contract.\nIt expires today.",
        requestId = "request-123",
        expiresAt = Clock.System.now() + 5.minutes,
    )

    @Test
    fun iso18013RoundTrip() {
        assertEquals(
            payload,
            TextAcceptanceTransaction.parseIso18013Request(
                TextAcceptanceTransaction.serializeIso18013Request(payload)
            )
        )
    }

    @Test
    fun openId4VpRoundTrip() {
        val request = TextAcceptanceTransaction.serializeOpenId4VpRequest(payload, listOf("pid"), null)
        assertEquals(payload, TextAcceptanceTransaction.parseOpenId4VpRequest(request))
        assertEquals(
            payload,
            TextAcceptanceTransaction.parseJson(request.encodeToByteArray().toBase64Url().encodeToByteString()).payload
        )
    }

    @Test
    fun responseMustMatchTextRequestIdAndExpiry() = runTest {
        val transactionData = TransactionData(
            type = TextAcceptanceTransaction,
            payload = payload,
            protocol = TransactionProtocol.ISO_18013_5,
            rawBytes = TextAcceptanceTransaction.serializeIso18013Request(payload).let {
                kotlinx.io.bytestring.ByteString(org.multipaz.cbor.Cbor.encode(it))
            }
        )
        TextAcceptanceTransaction.verifyMdocResponse(transactionData, mapOf(
            "text" to payload.text.toDataItem(),
            "requestId" to payload.requestId.toDataItem(),
            "expiresAt" to payload.expiresAt.toString().toDataItem(),
        ))
        assertFailsWith<IllegalStateException> {
            TextAcceptanceTransaction.verifyMdocResponse(transactionData, mapOf(
                "text" to "Changed".toDataItem(),
                "requestId" to payload.requestId.toDataItem(),
                "expiresAt" to payload.expiresAt.toString().toDataItem(),
            ))
        }
        assertFailsWith<IllegalStateException> {
            TextAcceptanceTransaction.verifyMdocResponse(transactionData, mapOf(
                "text" to payload.text.toDataItem(),
                "requestId" to "different-request".toDataItem(),
                "expiresAt" to payload.expiresAt.toString().toDataItem(),
            ))
        }
    }

    @Test
    fun rejectsExpiredAndBidiControlledRequests() {
        assertFailsWith<IllegalArgumentException> {
            TextAcceptanceTransaction.serializeIso18013Request(payload.copy(expiresAt = Clock.System.now()))
        }
        assertFailsWith<IllegalArgumentException> {
            TextAcceptanceTransaction.serializeIso18013Request(payload.copy(text = "accept\u202e"))
        }
    }
}
