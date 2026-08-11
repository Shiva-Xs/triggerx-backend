# ⚡️ TriggerX

<div align="center">
  <p><strong>A high-performance, real-time cryptocurrency price alert system built with Spring Boot 3 & Java 21.</strong></p>
  <p>🚀 <em>The application is accessible at <strong><a href="https://www.triggerx.in">www.triggerx.in</a></strong></em></p>
  <p><em>Frontend: <a href="https://github.com/Shiva-Xs/triggerx-frontend">triggerx-frontend</a></em></p>
</div>

TriggerX enables users to set customizable price targets on Binance trading pairs. It delivers instant, zero-latency notifications via **Email** and **Telegram** when specified price thresholds are met.

By leveraging Binance's free real-time WebSocket stream, TriggerX securely supports up to 50 active alerts per user without incurring API costs.

---

## ✨ Features

- **Real-Time Market Data**: Direct Binance WebSocket integration streams miniTicker prices for highly efficient, free-of-cost price monitoring.
- **Natural Language Processing**: Groq API integration (`llama-3.1-8b-instant` via Spring AI) allows the creation of alerts using conversational English (e.g., "Alert me when ETH drops below 2500" or "BTC up 10%").
- **Percentage-Based Alerts**: NLP resolves relative targets from live prices automatically (e.g., "notify me if SOL falls 5%" derives the absolute target on the fly).
- **Passwordless Authentication**: Secure OTP-over-email login issuing 30-day JWT sessions, with per-user token versioning that makes every issued token instantly revocable through a single `logout-all` call.
- **Telegram Bot Integration**: Full webhook-free bot integration via direct linking. Receive instant alerts or interact directly with the bot to create, list, and delete alerts via natural language commands.
- **Duplicate & Limit Guards**: Enforces a configurable per-user alert cap (default 50) and rejects exact-duplicate active alerts before they hit the database.
- **High Concurrency**: Carefully engineered atomic database updates prevent race conditions and duplicate notification dispatches.

---

## 🛠 Technology Stack

| Category | Technologies |
|---|---|
| **Core** | Java 21, Spring Boot 3.3.x |
| **Database** | PostgreSQL, Flyway (Migrations), Spring Data JPA |
| **Security** | Custom JWT Filter, OTP Verification |
| **Integrations** | Gmail SMTP, Telegram Bot API, Spring AI + Groq (`llama-3.1-8b-instant`) |
| **Realtime** | `Java-WebSocket` for Binance Streaming |

---

## 🏗 Architecture Overview

```mermaid
graph TD
    Client[API Clients] -->|HTTP/JWT| API[TriggerX REST API]
    Extension[Chrome Extension] -->|Ephemeral Token| API
    Telegram[Telegram Client] -->|Bot API| TGBot[Telegram Bot Service]

    API --> AlertService
    TGBot --> AI[NaturalAlertService / Spring AI]
    AI --> AlertService
    API --> AI

    AlertService -->|AlertChangedEvent| WsSub[WebSocket Subscription Manager]
    AlertService --> DB[(PostgreSQL)]

    Binance[Binance WebSocket stream] -.->|Real-time Prices| PStream[BinanceWebSocketService]
    WsSub --> PStream
    PStream --> Matcher[Condition Matcher]
    DB -.->|Active Alerts| Matcher

    Matcher -->|Alert Triggered| Event[AlertFiredEvent]
    Event --> Notifier[NotificationService]

    Notifier --> Email[EmailService]
    Notifier --> TGSender[TelegramBotService]
```

## 🚀 Getting Started

### Prerequisites
- **Java 21**
- **Maven**
- **Docker** and **Docker Compose** (for running PostgreSQL locally)

### Local Development Setup

```bash
# 1. Boot up the PostgreSQL instance using the pre-configured docker-compose file
docker-compose up -d

# 2. Start the application with the 'dev' profile
# Note: OTP codes will print to the console. Telegram bot is disabled by default. No Gmail configuration required.
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

The application will be accessible at `http://localhost:8080`.

### Natural Language Configuration

To enable the AI parsing for the `/api/v1/alerts/natural` endpoint, provide a free Groq API key:

1. Create a file named `application-local.properties` (gitignored by default) in `src/main/resources`.
2. Add your key:

```properties
spring.ai.openai.api-key=your_groq_key_here
```

Restart the server to apply the changes. Note: Without this key, the NLP endpoint returns an HTTP 503 response, while all other features continue to operate normally.

---

## 🧪 Testing via Postman & Telegram Bot

You can manually verify all functionalities natively through **Postman** (or your preferred API client) along with the **Telegram Bot**.

### Testing with Postman
1. **Send OTP:** Issue a `POST /api/v1/auth/otp/send` with your email address to receive an authorization code. This code will be printed directly to your console (in dev mode) or sent to your email (in active environments).
2. **Verify & Tokenize:** Request `POST /api/v1/auth/otp/verify` using the email and the provided OTP to obtain a JWT.
3. **Execute API Calls:** Inject the obtained JWT token into your Postman environment under the `Authorization` header (`Bearer <jwt>`) to authenticate requests to the protected `/api/v1/*` endpoints.

### Telegram Live Testing
Once logged in, link your account to `@TriggerX_AlertBot` directly via the URL generated from the `/api/v1/telegram/link-token` endpoint. Once linked:
- Watch live notifications arrive instantly across Telegram as your simulated alerts trigger.
- Message the bot directly (e.g., "Send me a notification when Bitcoin passes 100k" or "show my alerts" or "delete all") and observe the NLP engine securely provisioning and managing alerts against your account.

---

## 📖 API Documentation

Complete overview of the available REST API endpoints:

### Authentication Endpoints
| Method | Endpoint | Auth Required | Description |
|---|---|---|---|
| `POST` | `/api/v1/auth/otp/send` | No | Send OTP confirmation code to the user's email |
| `POST` | `/api/v1/auth/otp/verify` | No | Verify OTP code and return an authentication JWT |
| `POST` | `/api/v1/auth/logout-all` | Yes | Revoke every JWT ever issued to the caller by bumping their token version |

### Alert Management Endpoints
| Method | Endpoint | Auth Required | Description |
|---|---|---|---|
| `POST` | `/api/v1/alerts` | Yes | Create a structured alert from explicit symbol, condition, and target price |
| `POST` | `/api/v1/alerts/natural` | Yes | Parse natural English text to generate a structured alert |
| `GET`  | `/api/v1/alerts` | Yes | Fetch alerts for the authenticated user; optional `?status=ACTIVE\|TRIGGERED` filter |
| `GET`  | `/api/v1/alerts/counts` | Yes | Return `{"active": N, "triggered": N}` summary counts for the user |
| `GET`  | `/api/v1/alerts/{id}` | Yes | Fetch complete details for a specific alert |
| `DELETE` | `/api/v1/alerts/{id}` | Yes | Delete a specific alert by its ID |

### Symbols & System Endpoints
| Method | Endpoint | Auth Required | Description |
|---|---|---|---|
| `GET`  | `/api/v1/symbols/search?q=` | No | Returns search and autocomplete results for ticker symbols |
| `GET`  | `/api/v1/health` | No | Returns WebSocket status, last price timestamp, and active alert count |

### Integrations Endpoints
| Method | Endpoint | Auth Required | Description |
|---|---|---|---|
| `POST` | `/api/v1/telegram/link-token` | Yes | Dynamically generates a Telegram bot connecting deep-link |
| `POST` | `/api/v1/extension/auth-token` | Yes | Issue a temporary token to pass sessions to browser extensions |
| `POST` | `/api/v1/extension/redeem` | No | Redeem the ephemeral token to provision an extension JWT |

> **Authentication Header:** All protected endpoints require a valid JWT header provided as `Authorization: Bearer <token>`

---

## 🧩 Technical Implementation Details

1. **Precision & Integrity:** Cryptocurrency prices are tracked strictly using `DECIMAL(19,8)` within PostgreSQL. Traditional floating-point approximations are avoided to ensure precise market boundary triggering.
2. **Event-Driven WebSocket Management:** `AlertService` publishes an `AlertChangedEvent` on every create or delete. `BinanceWebSocketService` listens and dynamically subscribes or unsubscribes miniTicker streams — ensuring the WebSocket only carries streams with at least one live alert, and closes entirely when no alerts remain.
3. **Concurrency Isolation:** The backend gracefully solves concurrency via atomic SQL matching clauses (`UPDATE ... WHERE status = 'ACTIVE'`). This mitigates potential race conditions when multithreaded systems intercept shared asset pricing updates.
4. **Percentage Alert Resolution:** For `PCT_ALERT` intents, the backend resolves the absolute target price from the live WebSocket cache, falling back to the Binance REST API (`/api/v3/ticker/price`) if no cached value is available yet — ensuring percentage-based inputs always succeed.
5. **Duplicate & Limit Enforcement:** `AlertService` enforces a configurable per-user cap (`alert.max.per.user`, default 50) and performs an exact-match duplicate check on `(userId, symbol, condition, targetPrice, ACTIVE)` before persisting, returning a 409 response for conflicts.
6. **Browser Extension Ephemeral Login:** Secure session mirroring to browser extensions employs a 120-second sliding-window token exchange mechanism, allowing instant multi-platform continuity.
7. **Revocable Sessions:** Each JWT embeds the user's current `token_version`. `JwtFilter` validates that claim against the database on every request, so a single `POST /api/v1/auth/logout-all` increments the column and instantly invalidates every token that user holds — including ones minted through the extension refresh flow — without affecting any other user.
8. **Authenticated Telegram Webhook:** The public webhook verifies Telegram's `X-Telegram-Bot-Api-Secret-Token` header (constant-time comparison) before processing any update, so forged `Update` payloads are rejected.

---

## 🚢 Deployment Configuration

TriggerX is deployed to **Azure App Service (`triggerx-api1`)** on the **B2 plan** (Canada Central), connected to an **Azure Database for PostgreSQL Flexible Server**. To build and push a new release:

```bash
# Compile and package the artifact, skipping local unit checks
./mvnw clean package -DskipTests

# Deploy the JAR directly to the App Service
az webapp deploy \
  --resource-group triggerx \
  --name triggerx-api1 \
  --src-path target/triggerx-0.0.1-SNAPSHOT.jar \
  --type jar
```

### Environment Variables Matrix
Application properties mandate these parameters supplied externally in production environments:

- `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` — PostgreSQL connection details
- `JWT_SECRET` — Secure, pseudo-random signing secret (32+ chars length)
- `MAIL_USERNAME`, `MAIL_PASSWORD` — Gmail SMTP credentials or App-Specific Password
- `TELEGRAM_BOT_TOKEN`, `TELEGRAM_BOT_USERNAME` — Assigned from Telegram BotFather platform
- `TELEGRAM_WEBHOOK_SECRET` — Shared secret echoed by Telegram in the `X-Telegram-Bot-Api-Secret-Token` header; the webhook rejects any call without it (required when the bot is enabled)
- `SPRING_AI_OPENAI_API_KEY` — Groq API key for the NLP alert parsing endpoint

*Note: Container orchestration routes health verifications via the pre-configured `/api/v1/health` and `/actuator/health` endpoint mappings bound inherently.*
