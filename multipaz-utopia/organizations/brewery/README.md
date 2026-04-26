# Utopia Brewery — Multipaz Demo

An end-to-end e-commerce demo where customers browse a craft spirits catalog, add items to their cart, and **authorize the purchase by presenting a digital identity credential** (mDL, EU PID, Aadhaar, etc.) via the Multipaz verifier flow. Age is verified automatically during the credential check — no manual ID inspection required.

> Part of the [multipaz-utopia](../../README.md) fictional-world demos.

---

## Module Layout

```
brewery/
├── backend/    # Ktor/Netty server — verifier + /checkout API
└── frontend/   # Static HTML/CSS/JS storefront
```

### `backend`

Gradle project: `:multipaz-utopia:organizations:brewery:backend`

| File | Purpose |
|---|---|
| `Main.kt` | Entry point — wires `DocumentTypeRepository`, `TrustManagerInterface`, and `BreweryVerifierAssistant` then starts the server |
| `ApplicationExt.kt` | Mounts verifier endpoints + the `/checkout` route |
| `BreweryHandler.kt` | `/checkout` request handler and `BreweryVerifierAssistant` (age-check logic, DCQL builder, records stub) |

**Server port:** `8009`  
**URL prefix (behind nginx):** `/brewery/`

### `frontend`

Gradle project: `:multipaz-utopia:organizations:brewery:frontend`

Static resources served directly from the backend classpath (copied via `processResources`):

| File | Purpose |
|---|---|
| `index.html` | Product catalog grid |
| `product.html` | Product detail + "Buy" checkout flow |
| `brewery.css` | Storefront styles |
| `brewery.js` | Checkout orchestration — calls `/checkout`, drives `multipazVerifyCredentials()` |
| `images/` | Product photography (bourbon, gin, rum, scotch, vodka, rye, pour, distillery) |

---

## How It Works

```
Browser                          Brewery Backend              Wallet App
  │                                    │                           │
  │  POST /checkout                    │                           │
  │  { productName, price }  ────────► │                           │
  │                                    │  build DCQL +             │
  │  { dcql, transaction_data } ◄───── │  BreweryPurchaseTransaction│
  │                                    │                           │
  │  multipazVerifyCredentials()        │                           │
  │  (QR / deep-link) ────────────────────────────────────────────►│
  │                                    │                           │
  │                                    │ ◄── credential response ──│
  │                                    │  age-check + sign         │
  │  { approved / denied } ◄────────── │                           │
```

1. The user taps **Buy** on a product page.
2. `brewery.js` POSTs `{productName, price}` to `/checkout`.
3. The backend builds a DCQL request scoped to the `payment` credential ID, embedding a `BreweryPurchaseTransaction` in `transaction_data`.
4. The browser passes the response to `multipazVerifyCredentials()` which displays a QR code / deep-link for the wallet.
5. The wallet presents the credential; the backend's `BreweryVerifierAssistant` verifies age (≥ 18) and signs off on the transaction.
6. The browser receives an approved/denied result and shows the outcome to the user.

---

## Age Verification Logic

`BreweryVerifierAssistant` checks the following claims in priority order:

| Claim | Type | Credential(s) |
|---|---|---|
| `age_over_21` | Boolean | mDL |
| `age_over_18` | Boolean | mDL, EU PID |
| `age_above18` | Boolean | Aadhaar |
| `age_in_years` | Integer ≥ 18 | mDL, EU PID |
| `birth_date` | ISO date string | fallback |

Supported credential types: `photoid`, `mdl`, `eupid`, `aadhaar`.

---

## Running Locally

```bash
./gradlew multipaz-utopia:organizations:brewery:backend:run
```

The server starts on `http://localhost:8009`. Open `http://localhost:8009/` in your browser to see the storefront.

### Running Inside Docker (full stack)

```bash
./gradlew collectDependencies
./gradlew buildDockerImage
./gradlew runDockerImage
```

The brewery site will be available at `http://localhost:8000/brewery/`.

### Testing with an Android Wallet on a Real Device

When the wallet runs on a physical Android device (or another machine on
your network), `localhost` resolves to the device itself, so OID4VP request
URIs embedded by the verifier — `request_uri`, `response_uri`,
`redirect_uri` — must point at your dev machine's LAN IP, not `localhost`.

Find your LAN IP (`ip addr` / `ifconfig`), then pass it as `base_url`:

```bash
./gradlew :multipaz-utopia:organizations:brewery:backend:run \
  --args="-param base_url=http://192.168.1.7:8006"
```

For the full Docker stack, set the `BASE_URL` env var instead:

```bash
BASE_URL=http://192.168.1.7:8000 ./gradlew runDockerImage
```

Without this, the wallet fails with
`java.net.ConnectException: Failed to connect to localhost/127.0.0.1:<port>`.

---

## Transaction Type

The `BreweryPurchaseTransaction` (defined in `multipaz-utopia` shared module) carries four fields through the credential presentation flow:

| Field | Type | Example |
|---|---|---|
| `merchant` | String | `"Utopia Brewery"` |
| `description` | String | `"Aged Bourbon Whiskey"` |
| `amount` | String | `"72.00"` |
| `currency` | String | `"USD"` |

These fields are round-tripped in the device-signed namespace so the verifier can confirm the holder authorized the exact transaction.
