# WYSIWYS Text Acceptance Implementation Plan

## Status

Proposed implementation plan. No production protocol or API is committed by
this document.

## Objective

Implement an experimental WYSIWYS text-acceptance transaction. A verifier
requests an exact short statement; the holder reviews it and presents an
eligible identity credential whose device key signs evidence containing that
statement.

Example:

> I accept contract number PLUMB-2026-0042 from Acme Plumbing Ltd. on 16
> October 2026 for INR 2,000.

The initial demonstration uses two `samples/testapp` instances over ISO
18013-5 QR/BLE proximity presentment. The credential issuer is a separate
role: the TestApp fixture mimics its issuance policy initially, and a web
issuer integration is a later test phase.

## Scope

### Included in V1

- A typed `TextAcceptanceTransaction` implementation.
- ISO 18013-5 and OpenID4VP serialization, parsing, response generation, and
  verifier-side response validation.
- Plain-text holder UI in Compose and SwiftUI.
- Test PID/mDL fixture authorization for the transaction type.
- A canned EU PID request carrying a sample acceptance statement.
- Unit and two-device manual tests.

### Excluded from V1

- PDF, HTML, attachment, or arbitrary document signing.
- A visible PDF signature or PAdES signature.
- A dedicated contract-signing key separate from the identity credential.
- A production web issuer, contract backend, receipt service, or legal
  signature policy.
- NFC holder presentment on iOS.

## Existing Implementation Anchors

| Purpose | Existing anchor |
| --- | --- |
| Generic transaction lifecycle | `multipaz/.../documenttype/TransactionType.kt` |
| Existing transaction example | `multipaz-utopia/.../knowntypes/PingTransaction.kt` |
| Registration and canned request | `multipaz-utopia/.../knowntypes/DocumentTypeRepositoryExt.kt` |
| Device-key authorization in test credentials | `samples/testapp/.../TestAppUtils.kt` |
| Compose consent rendering | `multipaz-compose/.../presentment/Consent.kt` |
| SwiftUI consent rendering | `multipaz-swiftui/.../Consent.swift` |
| TestApp verifier request flow | `samples/testapp/.../ui/DcRequestScreen.kt` |

`PingTransaction` is the closest implementation template. It already supports
ISO mdoc and OpenID4VP transaction encodings, includes device-signed response
elements, and verifies those returned elements.

## Protocol Decisions

### Transaction identifier

Use the project-owned identifier below for the experimental feature:

```text
org.multipaz.transaction.text_acceptance
```

This follows the existing `PingTransaction` identifier convention. It is a
transaction-type and mdoc data-element identifier, not a credential type and
not a cryptographic algorithm identifier.

### Payload

```kotlin
data class TextAcceptancePayload(
    val text: String,
    val requestId: String,
    val expiresAt: Instant,
)
```

The statement is immutable. A verifier requiring changed terms creates a new
request with a new `requestId`.

### What is signed

For ISO mdoc, the wallet places the following values in the transaction's
`DeviceSigned` response data:

```text
text
requestId
expiresAt
docRequestId
```

The selected credential's device private key signs `DeviceAuthentication`,
which covers the resulting device-signed data and the presentation session.
The verifier compares each returned value to its original request.

For OpenID4VP, the existing transaction-data mechanism binds the raw
transaction data hash to the presentation. The transaction response also
carries the required request metadata where appropriate.

V1 returns the short statement itself rather than only a hash. This makes the
mdoc proof direct and follows the existing `PingTransaction` pattern. A future
privacy-oriented profile may return a canonical statement hash instead; that
requires an explicit canonical byte encoding specification and test vectors.

### Credential eligibility

An mdoc credential is eligible only when its MSO authorizes the device key for
the transaction data element:

```kotlin
deviceKeyAuthorizedDataElements = mapOf(
    ISO_18013_TRANSACTION_DATA_NAMESPACE to listOf(
        TextAcceptanceTransaction.identifier
    )
)
```

For OpenID4VP mdoc responses, also authorize
`TextAcceptanceTransaction.openId4VpMdocResponseNamespace` as required by the
existing transaction applicability check.

The wallet must not offer an otherwise valid PID/mDL if this authorization is
absent.

## Implementation Phases

### Phase 0 — Freeze the V1 wire contract

Decide and document before writing implementation code:

- Transaction identifier and stable payload field names.
- Maximum text length and request-ID limits.
- UTC representation and maximum validity window for `expiresAt`.
- Whether exact line-feed characters are allowed and preserved.
- Which text controls are rejected.
- Whether transaction responses return `expiresAt` in addition to `text` and
  `requestId`.

Acceptance criteria:

- A one-page wire-format note exists.
- Positive and negative examples are agreed by issuer, wallet, and verifier
  implementers.

### Phase 1 — Implement the transaction type

Add:

```text
multipaz-utopia/src/commonMain/kotlin/org/multipaz/utopia/knowntypes/
    TextAcceptanceTransaction.kt
```

Implement, following `PingTransaction`:

- `serializeIso18013Request()` and `parseIso18013Request()`.
- `serializeOpenId4VpRequest()` and `parseOpenId4VpRequest()`.
- `parseJson()` preserving the received OpenID4VP transaction-data bytes.
- `generateMdocResponseElements()` returning the exact statement, request ID,
  expiry, and `docRequestId` from the base implementation.
- `generateSdJwtResponseClaims()` with equivalent values for ISO 18013-5
  SD-JWT credentials.
- `verifyMdocResponse()` and `verifySdJwtResponse()` comparing every returned
  value with the request payload.
- A sample `CannedTransactionData` value for demos.

Validation rules must reject:

- blank or oversized text;
- blank or oversized request IDs;
- expired requests;
- unsupported control characters;
- Unicode bidi controls;
- payloads containing unrecognized critical fields.

Do not use `Double`, raw private key bytes, or a generic untyped map in this
public model.

### Phase 2 — Register the type and create a canned request

Modify:

```text
multipaz-utopia/.../knowntypes/DocumentTypeRepositoryExt.kt
```

- Register `TextAcceptanceTransaction` in `addUtopiaTypes()`.
- Add a separate canned EU PID request, for example
  `EU PID with Text Acceptance`, rather than changing the Ping request.
- Request only the identity claims needed by the demo: initially given name,
  family name, and birth date, consistent with the existing EU PID fixture.
- Give the canned statement a fresh-per-run request ID in the TestApp request
  construction. Do not ship a reusable fixed request ID as a realistic sample.

Acceptance criteria:

- Both holder and verifier repositories recognize the transaction type.
- An unknown transaction type continues to be rejected rather than rendered as
  a generic approval prompt.

### Phase 3 — Issue an eligible test credential

Modify the local TestApp credential fixture in:

```text
samples/testapp/src/commonMain/kotlin/org/multipaz/testapp/TestAppUtils.kt
```

For the EU PID/mDL demo credential:

- Add `TextAcceptanceTransaction.identifier` to
  `ISO_18013_TRANSACTION_DATA_NAMESPACE` in
  `deviceKeyAuthorizedDataElements`.
- Add the transaction's OpenID4VP mdoc response namespace to
  `deviceKeyAuthorizedNamespaces`.
- Preserve the existing Ping and payment authorizations; do not replace them.

The fixture models what an independent issuer must do in production: issue a
credential whose MSO authorizes the device key for this specific transaction
type.

Acceptance criteria:

- The new fixture credential is eligible.
- An equivalent fixture intentionally lacking the authorization is excluded by
  `TransactionType.isApplicable()`.

### Phase 4 — Add WYSIWYS consent rendering

Modify both reusable UI modules:

```text
multipaz-compose/src/commonMain/kotlin/org/multipaz/compose/presentment/Consent.kt
multipaz-swiftui/src/iosMain/swift/Consent.swift
```

Add a `TextAcceptanceTransaction` branch alongside the existing Ping and
Payment transaction branches.

The view must show:

- trusted requester identity from the presentment request, separate from text
  supplied by the transaction payload;
- the complete plain-text statement, preserving permitted line feeds;
- absolute expiry time and an optional short relative-expiry label;
- a specific confirmation action such as `Accept and sign`;
- a decline action.

The view must not:

- render HTML, Markdown, URLs, or arbitrary rich text;
- truncate or collapse the statement without an explicit full-text view;
- refresh or mutate the statement after confirmation begins.

Add localization keys through the existing Lokalize resource process if new
user-facing labels are introduced. Regenerate checked-in translation sources.

Acceptance criteria:

- Compose and SwiftUI display the same payload content and required actions.
- A malformed or expired payload cannot reach an approval action.

### Phase 5 — Wire the TestApp verifier request

Use the existing request construction in:

```text
samples/testapp/src/commonMain/kotlin/org/multipaz/testapp/ui/DcRequestScreen.kt
```

and the existing ISO proximity reader path to expose the new canned request.

For every run, TestApp A must create:

```text
- exact statement text;
- fresh cryptographically random request ID;
- short expiry time;
- signed/trusted verifier identity;
- a pending-request record in memory for the demo.
```

After the response, TestApp A must verify presentation and transaction data
before displaying success. It must reject a duplicate request ID, expired
request, mismatched statement, or missing transaction evidence.

The initial proximity demonstration is:

```text
TestApp A (verifier) shows QR → TestApp B scans and connects over BLE
→ TestApp B displays text and presents eligible EU PID/mDL
→ TestApp A verifies and displays the result.
```

Use QR/BLE for cross-platform holder testing. Android NFC presentation can be
tested separately; Multipaz does not support NFC credential presentation on
iOS.

### Phase 6 — Tests

Create transaction unit tests under:

```text
multipaz-utopia/src/commonTest/kotlin/org/multipaz/utopia/knowntypes/
```

Cover at least:

- ISO and OpenID4VP serialize/parse round trips;
- returned mdoc response matches the original text, request ID, and expiry;
- changed text is rejected;
- changed request ID is rejected;
- expired request is rejected;
- malformed text is rejected;
- an unauthorized mdoc credential is not applicable;
- supported SD-JWT transaction behavior, if included in the initial scope.

Extend existing core presentment/OpenID4VP tests where needed to demonstrate
that a full credential presentation verifies with this transaction type.

### Phase 7 — Independent web issuer integration

Keep this separate from the feature implementation. Once the two-TestApp
fixture is working, test with an independent issuer that provisions the same
device-key authorization into the PID/mDL MSO.

Verify the end-to-end roles remain separate:

```text
Web issuer → issues eligible PID/mDL to TestApp B
TestApp A  → acts only as plumber/verifier
TestApp B  → reviews text and presents its PID/mDL
```

This phase must not rely on TestApp A issuing the credential.

## Verification Plan

Run after implementation:

```shell
./gradlew :multipaz-utopia:jvmTest
./gradlew :multipaz-compose:compileKotlinJvm
./gradlew :samples:testapp:assembleDebug
./gradlew detektMetadataCommonMain
```

Then perform a physical two-device test:

1. Install TestApp on two devices.
2. Provision TestApp B with the eligible EU PID/mDL fixture.
3. Use TestApp A to create a fresh text-acceptance request.
4. Complete QR/BLE presentment and holder authentication.
5. Confirm TestApp A reports the expected holder identity and exact statement.
6. Repeat with altered text, expired request, duplicate request ID, and an
   unauthorized credential; all must fail.

## Delivery Gates

| Gate | Required evidence |
| --- | --- |
| Wire contract | Reviewed payload and validation rules |
| Core behavior | Unit tests for serialization and verification failures |
| Credential policy | Authorized and unauthorized fixture credentials |
| UX | Compose and SwiftUI parity review |
| Protocol | Successful ISO proximity transaction and verifier validation |
| Security | Replay, expiry, malformed text, and mismatch tests pass |
| External readiness | Independent issuer can issue equivalent authorization |

## Follow-up: PDF Acceptance

Only begin after text acceptance passes all delivery gates. Add a separate
`PdfAcceptanceTransaction`; do not overload the text payload. It should bind
an immutable PDF digest, hash-algorithm identifier, request ID, expiry, and
holder-facing statement. The wallet must hash the retrieved PDF bytes and
reject a mismatch before rendering or signing.
