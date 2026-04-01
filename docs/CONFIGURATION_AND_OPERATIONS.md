# Configuration, Error Handling, Logging, and Operations

## 1) Application Properties

File: `src/main/resources/application.properties`

## Core Settings
- `spring.application.name`
- `server.port`

## Gemini / Spring AI
- `spring.ai.google.genai.api-key`
- `spring.ai.google.genai.project-id`
- `spring.ai.google.genai.chat.options.model`

## Google OAuth Client
- `spring.security.oauth2.client.registration.google.client-id`
- `spring.security.oauth2.client.registration.google.client-secret`
- `spring.security.oauth2.client.registration.google.scope`

## JWT
- `jwt.secret` (Base64-encoded secret)
- `jwt.expiration-ms`

## Cloud Run Logging
- `gcp.project-id` (defaults from `GOOGLE_CLOUD_PROJECT`)

---

## 2) Security and Authorization Flow

1. `AuthController` verifies Google token and issues app JWT.
2. Client includes JWT in `Authorization` header.
3. `JwtAuthenticationFilter` validates token each request.
4. Valid token creates security context authentication.
5. `SecurityConfiguration` permits or blocks request by endpoint rules.

---

## 3) Exception Handling Strategy

Global handling lives in `src/main/java/com/codapt/quizapp/controller/GlobalExceptionHandler.java`.

## Mapped exception types
- `NoResourceFoundException` -> 404
- `HttpMessageNotReadableException` -> 400
- `IllegalArgumentException` -> 400
- `RuntimeException` -> 400
- `Exception` -> 500

## Error payload shape
```json
{
  "timestamp": "2026-03-16T23:25:00.100",
  "message": "Request body is missing or malformed",
  "details": "...",
  "path": "/api/quiz/generate"
}
```

---

## 4) Logging Strategy

Logs are emitted in JSON to stdout using `src/main/resources/logback-spring.xml`, which is the format expected by Cloud Logging on Cloud Run.

## Cloud Run fields
- `severity`
- `message`
- `timestamp`
- `logging.googleapis.com/trace`
- `logging.googleapis.com/spanId`
- `logging.googleapis.com/trace_sampled`

## Request trace correlation
- `TraceCorrelationFilter` reads `X-Cloud-Trace-Context`.
- The filter writes trace/span values into MDC before request processing.
- `SecurityConfiguration` places this filter before JWT auth filter so downstream logs include trace context.

## Controller logs
- Request receipt and key progress markers.
- Validation warnings for bad requests.

## Service logs
- Caption extraction start/finish and metadata info.
- Gemini call start/success/failure.
- Token validation warnings for JWT parsing issues.

## Global exception logs
- Structured warnings/errors with request path and stack traces where relevant.

---

## 5) Build, Test, and Run

```powershell
# compile + test
./mvnw.cmd clean test

# run app
./mvnw.cmd spring-boot:run
```

---

## 6) External Dependencies and Integrations

- **YouTube metadata/captions**: via bundled `yt-dlp.exe` and subtitle URL download.
- **Google token validation**: Google API Client verifier.
- **Gemini quiz generation**: Spring AI + Google GenAI model.
- **OpenAPI docs**: Springdoc endpoints (`/v3/api-docs`, `/swagger-ui.html`).

---

## 7) Known Operational Notes

- Keep API keys and client secrets out of VCS in production; inject via environment or secrets manager.
- JWT signing secret must remain stable across restarts for token verification continuity.
- Gemini model availability is project/key dependent; invalid model name returns runtime errors from provider.

