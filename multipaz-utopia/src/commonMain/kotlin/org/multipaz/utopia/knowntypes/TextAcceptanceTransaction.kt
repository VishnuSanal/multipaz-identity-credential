package org.multipaz.utopia.knowntypes

import kotlinx.io.bytestring.ByteString
import kotlinx.io.bytestring.decodeToString
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNamingStrategy
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.multipaz.cbor.CborMap
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.buildCborMap
import org.multipaz.cbor.toDataItem
import org.multipaz.credential.Credential
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.Crypto
import org.multipaz.documenttype.CannedTransactionData
import org.multipaz.documenttype.TransactionType
import org.multipaz.documenttype.TransactionUserInput
import org.multipaz.presentment.TransactionData
import org.multipaz.presentment.TransactionProtocol
import org.multipaz.util.fromBase64Url
import org.multipaz.util.toBase64Url
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * An experimental transaction that binds acceptance of an exact plain-text statement to a
 * credential presentation.
 *
 * The text is deliberately restricted to plain text. Newlines are preserved; all other control
 * characters and Unicode bidirectional controls are rejected to prevent a holder and verifier
 * from seeing different statements.
 */
object TextAcceptanceTransaction: TransactionType<TextAcceptanceTransaction.Payload>(
    displayName = "Text acceptance",
    identifier = "org.multipaz.transaction.text_acceptance"
) {
    private const val MAX_TEXT_LENGTH = 4_096
    private const val MAX_REQUEST_ID_LENGTH = 128
    private val MAX_VALIDITY = 10.minutes

    @Serializable
    data class JsonData(
        val type: String,
        val credentialIds: List<String>,
        val transactionDataHashesAlg: List<String>?,
        val text: String,
        val requestId: String,
        val expiresAt: String,
    )

    data class Payload(
        val text: String,
        val requestId: String,
        val expiresAt: Instant,
    )

    override fun serializeIso18013Request(payload: Payload): DataItem {
        validate(payload)
        return buildCborMap {
            put("text", payload.text)
            put("requestId", payload.requestId)
            put("expiresAt", payload.expiresAt.toString())
        }
    }

    override fun parseIso18013Request(dataItem: DataItem): Payload {
        require(dataItem is CborMap) { "Text acceptance request must be a CBOR map" }
        require(dataItem.items.keys.map { it.asTstr }.toSet() == REQUIRED_FIELDS) {
            "Text acceptance request has missing or unrecognized fields"
        }
        return Payload(
            text = dataItem.getOrNull("text")!!.asTstr,
            requestId = dataItem.getOrNull("requestId")!!.asTstr,
            expiresAt = parseExpiry(dataItem.getOrNull("expiresAt")!!.asTstr)
        ).also(::validate)
    }

    override fun serializeOpenId4VpRequest(
        payload: Payload,
        credentialIds: List<String>,
        hashAlgorithms: List<Algorithm>?
    ): String {
        validate(payload)
        return jsonFormat.encodeToString(JsonData(
            type = identifier,
            credentialIds = credentialIds,
            transactionDataHashesAlg = joseHashAlgorithms(hashAlgorithms),
            text = payload.text,
            requestId = payload.requestId,
            expiresAt = payload.expiresAt.toString(),
        ))
    }

    override fun parseOpenId4VpRequest(jsonString: String): Payload =
        parseJsonData(jsonString)

    override fun parseJson(serialized: ByteString): TransactionData<Payload> {
        val jsonString = serialized.decodeToString().fromBase64Url().decodeToString()
        val data = jsonFormat.decodeFromString<JsonData>(jsonString)
        require(data.type == identifier) { "Unexpected transaction type '${data.type}'" }
        return TransactionData(
            type = this,
            payload = payloadFrom(data),
            protocol = TransactionProtocol.OPENID4VP,
            rawBytes = serialized,
            hashAlgorithms = parseJoseHashAlgorithms(data.transactionDataHashesAlg),
        )
    }

    override suspend fun generateMdocResponseElements(
        transactionData: TransactionData<Payload>,
        credential: Credential,
        userInput: TransactionUserInput?,
        docRequestId: Int?
    ): Map<String, DataItem> = buildMap {
        putAll(super.generateMdocResponseElements(transactionData, credential, userInput, docRequestId))
        put("text", transactionData.payload.text.toDataItem())
        put("requestId", transactionData.payload.requestId.toDataItem())
        put("expiresAt", transactionData.payload.expiresAt.toString().toDataItem())
    }

    override suspend fun generateSdJwtResponseClaims(
        transactionData: TransactionData<Payload>,
        credential: Credential,
        userInput: TransactionUserInput?,
        docRequestId: Int?
    ): Map<String, JsonElement> = buildMap {
        putAll(super.generateSdJwtResponseClaims(transactionData, credential, userInput, docRequestId))
        put("text", JsonPrimitive(transactionData.payload.text))
        put("request_id", JsonPrimitive(transactionData.payload.requestId))
        put("expires_at", JsonPrimitive(transactionData.payload.expiresAt.toString()))
    }

    override suspend fun verifyMdocResponse(
        transactionData: TransactionData<Payload>,
        responseElements: Map<String, DataItem>
    ) {
        super.verifyMdocResponse(transactionData, responseElements)
        if (transactionData.protocol == TransactionProtocol.ISO_18013_5) {
            verify(transactionData.payload, responseElements["text"]?.asTstr,
                responseElements["requestId"]?.asTstr, responseElements["expiresAt"]?.asTstr)
        }
    }

    override suspend fun verifySdJwtResponse(
        transactionData: TransactionData<Payload>,
        responseClaims: Map<String, JsonElement>
    ) {
        super.verifySdJwtResponse(transactionData, responseClaims)
        if (transactionData.protocol == TransactionProtocol.ISO_18013_5) {
            verify(transactionData.payload, responseClaims["text"]?.jsonPrimitive?.contentOrNull,
                responseClaims["request_id"]?.jsonPrimitive?.contentOrNull,
                responseClaims["expires_at"]?.jsonPrimitive?.contentOrNull)
        }
    }

    /** A short-lived sample. Production callers must create a fresh request ID for every request. */
    val sampleData: CannedTransactionData<Payload>
        get() = CannedTransactionData(
            transactionType = this,
            payload = Payload(
                text = "I accept contract number PLUMB-2026-0042 from Acme Plumbing Ltd.",
                requestId = Crypto.secureRandom.nextBytes(16).toBase64Url(),
                expiresAt = Clock.System.now() + MAX_VALIDITY,
            )
        )

    private fun parseJsonData(jsonString: String): Payload {
        val data = jsonFormat.decodeFromString<JsonData>(jsonString)
        require(data.type == identifier) { "Unexpected transaction type '${data.type}'" }
        return payloadFrom(data)
    }

    private fun payloadFrom(data: JsonData) = Payload(
        text = data.text,
        requestId = data.requestId,
        expiresAt = parseExpiry(data.expiresAt)
    ).also(::validate)

    private fun parseExpiry(value: String): Instant = try {
        Instant.parse(value)
    } catch (e: IllegalArgumentException) {
        throw IllegalArgumentException("Invalid text acceptance expiry", e)
    }

    private fun validate(payload: Payload) {
        require(payload.text.isNotBlank() && payload.text.length <= MAX_TEXT_LENGTH) {
            "Text acceptance text must be between 1 and $MAX_TEXT_LENGTH characters"
        }
        require(payload.requestId.isNotBlank() && payload.requestId.length <= MAX_REQUEST_ID_LENGTH) {
            "Text acceptance request ID must be between 1 and $MAX_REQUEST_ID_LENGTH characters"
        }
        require(payload.text.none(::isDisallowedTextCharacter)) { "Text acceptance text contains a disallowed character" }
        require(payload.requestId.none(::isDisallowedTextCharacter)) { "Text acceptance request ID contains a disallowed character" }
        val now = Clock.System.now()
        require(payload.expiresAt > now) { "Text acceptance request has expired" }
        require(payload.expiresAt - now <= MAX_VALIDITY) { "Text acceptance request validity exceeds $MAX_VALIDITY" }
    }

    private fun isDisallowedTextCharacter(character: Char): Boolean =
        (character.isISOControl() && character != '\n') || character in BIDI_CONTROLS

    private fun verify(expected: Payload, text: String?, requestId: String?, expiresAt: String?) {
        validate(expected)
        if (text != expected.text || requestId != expected.requestId || expiresAt != expected.expiresAt.toString()) {
            throw IllegalStateException("Text acceptance transaction response does not match the request")
        }
    }

    private val REQUIRED_FIELDS = setOf("text", "requestId", "expiresAt")
    private val BIDI_CONTROLS = setOf('\u061c', '\u200e', '\u200f', '\u202a', '\u202b', '\u202c', '\u202d', '\u202e', '\u2066', '\u2067', '\u2068', '\u2069')

    @OptIn(ExperimentalSerializationApi::class)
    private val jsonFormat = Json {
        explicitNulls = false
        namingStrategy = JsonNamingStrategy.SnakeCase
        ignoreUnknownKeys = false
    }
}
