# WYSIWYS Text Acceptance Implementation Plan

## Status

V1 was implemented (2026-11-06) against the then-current `main`. It passes
`:multipaz-utopia:jvmTest`, compiles into `multipaz-compose`, `samples/testapp`,
and the golden-screenshot test, and passes `detektMetadataCommonMain`. Remaining
work: a two-device QR/BLE manual test, localized translations (English base only
so far), and review of this document.

The earlier `WYSIWYS.patch` diff is obsolete; this document plus the
implementation in the working tree is the source of truth.

No production protocol or API is committed by this document.

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

### Wire contract (frozen for V1)

- Identifier: `org.multipaz.transaction.text_acceptance`.
- ISO 18013-5 request encoding: CBOR map with exactly the string keys
  `text`, `requestId`, `expiresAt` (all as text strings; no other keys are
  accepted).
- OpenID4VP request encoding: JSON object with `type`, `credential_ids`,
  `transaction_data_hashes_alg` (optional), `text`, `request_id`,
  `expires_at`; field order irrelevant, unknown fields rejected.
- `expiresAt` is serialized as an ISO-8601 UTC string (`Instant.toString()`)
  and must serialize to a future instant no more than 10 minutes away. Parsing
  rejects past or over-long expiries.
- Maximum text length: 4,096 characters. Maximum request ID length: 128
  characters. Both must be non-blank.
- The only permitted control character is LF (`\n`); all other ISO control
  characters and the Unicode bidi controls U+061C, U+200E, U+200F, U+202A-
  U+202E, U+2066-U+2069 are rejected. Newlines are preserved end to end.
- SD-JWT response claim names: `text`, `request_id`, `expires_at` under the
  transaction claim (lowercase, underscore-separated).

### What is signed

For ISO mdoc, the wallet places the following values in the transaction's
`DeviceSigned` response data:

```text
text
requestId
expiresAt
docRequestId
```

The `docRequestId` element is echoed by the `TransactionType` base
implementation; the transaction type itself only adds the three value fields
above.

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

- A one-page wire-format note exists. Status (2026-11-06): the frozen
  decisions are recorded in the "Wire contract" subsection under Protocol
  Decisions.
- Positive and negative examples are agreed by issuer, wallet, and verifier
  implementers.

### Phase 1 — Implement the transaction type

Status (2026-11-06): done. `TextAcceptanceTransaction.kt` implements every
bullet below; validation adds strict unknown-field rejection (exact CBOR map
equality for ISO, `ignoreUnknownKeys = false` for the OpenID4VP JSON).

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

Status (2026-11-06): done. The type is registered and a separate
"EU PID with Text Acceptance" (`withTextAcceptance`) canned request was added
requesting only family name, given name, and birth date; the Ping request is
untouched.

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

Status (2026-11-06): done for the authorized side. The payment-card fixture
also preserves its existing Ping/Payment authorizations; the mDL,
ZKP-friendly mDL, and EU PID fixtures authorize the new transaction element
and OpenID4VP response namespace alongside Ping. Remaining: no test yet that
an intentionally unauthorized fixture is excluded by
`TransactionType.isApplicable()` (that logic is inherited from the core
`TransactionType` and covered upstream); worth adding as a follow-up test.

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

Status (2026-11-06): done on both platforms. The SwiftUI branch follows the
hardcoded-English style of the rest of `Consent.swift` (that file does not use
the Compose resource system), while the Compose branch uses new Lokalize keys.
The SwiftUI change has not been compiled (no Xcode on the Linux build host);
run the Xcode build from the Verification Plan before release.

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

Decision (2026-11-06): V1 keeps the standard generic confirm button
(`Continue`) so no changes are needed to the consolidated More/Wait/Continue +
cool-off button flow. Rendering follows the payment/ping pattern: a statement
box shown verbatim as plain text plus an expiry row. An "Accept and sign" word
swap remains a candidate follow-up.

Decision (2026-11-06): the expiry is rendered as the ISO-8601 instant string in
V1. Follow-up: format it with the existing localized date/time helper
(`formattedDateTime()` in `multipaz-compose`, equivalent on iOS) so it is
holder-friendly instead of a raw instant string.

The view must not:

- render HTML, Markdown, URLs, or arbitrary rich text;
- truncate or collapse the statement without an explicit full-text view;
- refresh or mutate the statement after confirmation begins.

Add localization keys through the existing Lokalize resource process if new
user-facing labels are introduced. Regenerate checked-in translation sources.

Status (2026-11-06): the English base keys
(`credential_presentment_text_acceptance_tx_approved`,
`credential_presentment_text_acceptance_tx_also_approved`,
`credential_presentment_text_acceptance_tx_expires`) are added to
`multipaz-compose` string resources. The remaining translations still need the
Lokalize AI-fill step.

Acceptance criteria:

- Compose and SwiftUI display the same payload content and required actions.
- A malformed or expired payload cannot reach an approval action.

### Phase 5 — Wire the TestApp verifier request

Status (2026-11-06): the request-wiring code is done (see the implementation
note below). The physical two-device QR/BLE demonstration from the
Verification Plan is still pending.

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
- signed/trusted verifier identity.
```

Implementation note (2026-11-06): V1 has no separate in-memory pending-request
ledger. The canned request (`extra_withTextAcceptance` in the request screen)
is refreshed immediately before each send, replacing the short-lived payload
with a fresh `TextAcceptanceTransaction.sampleData` value, so every run gets a
new CSPRNG request ID and expiry. Replaying an older response therefore fails
the returned-value comparison against the current request.

After the response, TestApp A must verify presentation and transaction data
before displaying success. Verification goes through the standard transaction
path (`TransactionType.verifyMdocResponse()` / `verifySdJwtResponse()`), which
rejects a stale or duplicate request ID, an expired request, a mismatched
statement, or missing transaction evidence.

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

Status (2026-11-06): done for the transaction itself —
`multipaz-utopia/src/commonTest/.../TextAcceptanceTransactionTest.kt` (9 tests)
covers ISO and OpenID4VP round trips, `parseJson` with hash algorithms, the
response-match and mismatch cases, malformed text/IDs, unknown fields, and
expired/over-long validity. Remaining: no dedicated core presentment/OpenID4VP
end-to-end test for this transaction type yet (the type follows the
`PingTransaction` code path that upstream end-to-end tests already exercise);
add one in Phase 7 or later.

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

Status (2026-11-06): pending. The TestApp fixture mimics issuance (Phase 3);
this phase needs a real issuer provisioning the same device-key authorization
into the MSO, plus hands-on device testing.

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
./gradlew :samples:testapp:assembleDebug   # compiles the Compose consent path
./gradlew detektMetadataCommonMain
./gradlew :multipaz-compose:testDebugUnitTest \
  --tests "org.multipaz.compose.presentment.ConsentGoldenScreenshotTest" \
  -Pdisable.web.targets=true
```

Note (2026-11-06): the old `./gradlew :multipaz-compose:compileKotlinJvm`
command no longer exists; the Compose consent path compiles via
`:samples:testapp:assembleDebug` or any `multipaz-compose` platform target
(verified via `:multipaz-compose:compileKotlinIosSimulatorArm64`). All of the
commands above pass as of 2026-11-06. SwiftUI changes still require an Xcode
build, e.g.:

```shell
xcodebuild -project samples/SwiftTestApp/SwiftTestApp.xcodeproj \
  -scheme SwiftTestApp -sdk iphonesimulator build
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

| Gate | Required evidence | Status (2026-11-06) |
| --- | --- | --- |
| Wire contract | Reviewed payload and validation rules | Documented in the "Wire contract" subsection; awaiting team review |
| Core behavior | Unit tests for serialization and verification failures | Done — 9 unit tests pass (`:multipaz-utopia:jvmTest`) |
| Credential policy | Authorized and unauthorized fixture credentials | Authorized fixtures done; unauthorized-exclusion test pending |
| UX | Compose and SwiftUI parity review | Both implemented; SwiftUI uncompiled (Xcode needed), expiry format and button wording are open decisions for review |
| Protocol | Successful ISO proximity transaction and verifier validation | Pending — two-device manual test |
| Security | Replay, expiry, malformed text, and mismatch tests pass | Done at unit level; no physical device replay test yet |
| External readiness | Independent issuer can issue equivalent authorization | Pending — Phase 7 |

## Follow-up: PDF Acceptance

Only begin after text acceptance passes all delivery gates. Add a separate
`PdfAcceptanceTransaction`; do not overload the text payload. It should bind
an immutable PDF digest, hash-algorithm identifier, request ID, expiry, and
holder-facing statement. The wallet must hash the retrieved PDF bytes and
reject a mismatch before rendering or signing.
