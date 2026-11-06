package org.multipaz.utopia.knowntypes

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import kotlinx.serialization.SerializationException
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.buildCborMap
import org.multipaz.cbor.toDataItem
import org.multipaz.crypto.Algorithm
import org.multipaz.presentment.TransactionData
import org.multipaz.presentment.TransactionProtocol
import org.multipaz.util.toBase64Url

class TextAcceptanceTransactionTest {
    // Building the CBOR request validates the payload up-front (fresh request is not expired).
    private val payload = TextAcceptanceTransaction.Payload(
        text = "I accept contract number PLUMB-2026-0042.\nIt is a fair deal.",
        requestId = "request-123",
        expiresAt = Clock.System.now() + 5.minutes,
    ).also { TextAcceptanceTransaction.serializeIso18013Request(it) }

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
    }

    @Test
    fun parseJsonPreservesHashAlgorithmsAndBytes() {
        val request = TextAcceptanceTransaction.serializeOpenId4VpRequest(
            payload, listOf("pid"), listOf(Algorithm.SHA256))
        val serialized = ByteString(request.encodeToByteArray().toBase64Url().encodeToByteArray())
        val transactionData = TextAcceptanceTransaction.parseJson(serialized)
        assertEquals(payload, transactionData.payload)
        assertEquals(TransactionProtocol.OPENID4VP, transactionData.protocol)
        assertEquals(serialized, transactionData.rawBytes)
        assertEquals(listOf(Algorithm.SHA256), transactionData.hashAlgorithms)
    }

    @Test
    fun openId4VpRequestRejectsUnknownFields() {
        val request = TextAcceptanceTransaction.serializeOpenId4VpRequest(payload, listOf("pid"), null)
        val jsonWithExtraField = request.removeSuffix("}") + ", \"unexpected_critical_field\": true}"
        assertFailsWith<SerializationException> {
            TextAcceptanceTransaction.parseOpenId4VpRequest(jsonWithExtraField)
        }
    }

    @Test
    fun responseMustMatchTextRequestIdAndExpiry() = runTest {
        val transactionData = TransactionData(
            type = TextAcceptanceTransaction,
            payload = payload,
            protocol = TransactionProtocol.ISO_18013_5,
            rawBytes = ByteString(Cbor.encode(TextAcceptanceTransaction.serializeIso18013Request(payload)))
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
        assertFailsWith<IllegalStateException> {
            TextAcceptanceTransaction.verifyMdocResponse(transactionData, mapOf(
                "text" to payload.text.toDataItem(),
                "requestId" to payload.requestId.toDataItem(),
                "expiresAt" to (payload.expiresAt + 1.seconds).toString().toDataItem(),
            ))
        }
    }

    @Test
    fun rejectsBlankOversizedAndControlCharacterText() {
        assertFailsWith<IllegalArgumentException> {
            TextAcceptanceTransaction.serializeIso18013Request(payload.copy(text = " "))
        }
        assertFailsWith<IllegalArgumentException> {
            TextAcceptanceTransaction.serializeIso18013Request(payload.copy(text = "a".repeat(4_097)))
        }
        assertFailsWith<IllegalArgumentException> {
            TextAcceptanceTransaction.serializeIso18013Request(payload.copy(text = "accept\u202e"))
        }
        assertFailsWith<IllegalArgumentException> {
            TextAcceptanceTransaction.serializeIso18013Request(payload.copy(text = "one\u0000two"))
        }
    }

    @Test
    fun rejectsBlankAndOversizedRequestIds() {
        assertFailsWith<IllegalArgumentException> {
            TextAcceptanceTransaction.serializeIso18013Request(payload.copy(requestId = " "))
        }
        assertFailsWith<IllegalArgumentException> {
            TextAcceptanceTransaction.serializeIso18013Request(payload.copy(requestId = "r".repeat(129)))
        }
    }

    @Test
    fun rejectsExpiredAndExcessivelyLongLivedRequests() {
        assertFailsWith<IllegalArgumentException> {
            TextAcceptanceTransaction.serializeIso18013Request(
                payload.copy(expiresAt = Clock.System.now())
            )
        }
        assertFailsWith<IllegalArgumentException> {
            TextAcceptanceTransaction.serializeIso18013Request(
                payload.copy(expiresAt = Clock.System.now() + 10.minutes + 1.seconds)
            )
        }
    }

    @Test
    fun expiredRequestRejectedWhenParsed() {
        assertFailsWith<IllegalArgumentException> {
            TextAcceptanceTransaction.parseIso18013Request(
                buildCborMap {
                    put("text", payload.text)
                    put("requestId", payload.requestId)
                    put("expiresAt", (Clock.System.now() - 1.seconds).toString())
                }
            )
        }
        assertFailsWith<IllegalArgumentException> {
            TextAcceptanceTransaction.parseIso18013Request(
                buildCborMap {
                    put("text", payload.text)
                    put("requestId", payload.requestId)
                    put("expiresAt", "not-a-timestamp")
                }
            )
        }
    }
}
