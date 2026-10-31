
# VegGo Fresh Backend

Multi-vendor vegetable delivery platform — Spring Boot 3.x backend.

---

## Table of Contents

1. [Prerequisites](#prerequisites)
2. [Running Locally](#running-locally)
3. [Project Structure](#project-structure)
4. [Module Boundary Rules](#module-boundary-rules)
5. [Flyway Version Ranges](#flyway-version-ranges)
6. [API Contracts Reference](#api-contracts-reference)
7. [Environment Variables](#environment-variables)
8. [Branch Strategy](#branch-strategy)

---

## Prerequisites

| Tool          | Version     |
|---------------|-------------|
| Java          | 17+         |
| Maven         | 3.9+        |
| MySQL         | 8.0+        |
| Docker (optional) | 24+    |

---

## Running Locally

### 1. Start MySQL

**Option A — Docker Compose (recommended)**

```bash
docker run --name veggofresh-mysql \
  -e MYSQL_ROOT_PASSWORD=root \
  -e MYSQL_DATABASE=veggofresh_local \
  -p 3306:3306 \
  -d mysql:8.0
```

**Option B — Local MySQL installation**

Create the database manually:
```sql
CREATE DATABASE veggofresh_local CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

### 2. Configure Environment

Copy and populate the environment file:
```bash
cp .env.example .env
```

Minimum required variables for local development:
```dotenv
DB_URL=jdbc:mysql://localhost:3306/veggofresh_local?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true
DB_USERNAME=root
DB_PASSWORD=root
JWT_SECRET=<base64-encoded-256-bit-key>
```

Generate a JWT secret:
```bash
openssl rand -base64 32
```

### 3. Build and Run

```bash
# Compile and run with local profile
mvn spring-boot:run -Dspring-boot.run.profiles=local

# Or compile first, then run the JAR
mvn clean package -DskipTests
java -jar target/veggofresh-backend-0.0.1-SNAPSHOT.jar --spring.profiles.active=local
```

### 4. Verify

| URL | Expected Result |
|-----|-----------------|
| `http://localhost:8080/swagger-ui.html` | Swagger UI loads (no endpoints in Phase 0) |
| `http://localhost:8080/actuator/health` | `{"status":"UP"}` |

---

## Project Structure

```
veggofresh-backend/
├── docs/
│   └── API_CONTRACTS.md          ← API conventions (read this first!)
├── src/
│   └── main/
│       ├── java/com/veggofresh/
│       │   └── platform/         ← Phase 0: shared infrastructure only
│       │       ├── common/       ← BaseEntity, ApiResponse, PageResponse
│       │       ├── exception/    ← BusinessException, GlobalExceptionHandler
│       │       └── security/     ← JWT, SecurityConfig, DeviceIdFilter
│       └── resources/
│           ├── application.yml
│           ├── application-local.yml
│           ├── application-dev.yml
│           ├── application-prod.yml
│           └── db/migration/
│               └── V1__init_schema.sql
└── pom.xml
```

**Future module packages (Phase 1+):**

```
com.veggofresh.auth.*
com.veggofresh.customer.*
com.veggofresh.vendor.*
com.veggofresh.delivery.*
com.veggofresh.admin.*
com.veggofresh.payment.*
com.veggofresh.notification.*
```

---

## Module Boundary Rules

> ⚠️ **These rules are enforced by code review. Violations will cause PR rejections.**

1. **Each module owns its package.** A module's code lives exclusively under its package
   (e.g., `com.veggofresh.vendor.*`). No exceptions.

2. **No direct entity imports across module boundaries.**  
   ❌ `com.veggofresh.customer` importing `com.veggofresh.vendor.entity.Vendor`  
   ✅ Cross-module data flows through `@Service` interface contracts using DTOs.

3. **Flyway migrations are range-isolated.** A module may only create migrations
   within its assigned version range (see below). Never use another module's range.

4. **Shared infrastructure goes in `com.veggofresh.platform`.**  
   If multiple modules need the same utility, add it to the platform package — not to a specific module.

5. **No shared JPA repositories across modules.**  
   Each module owns its own repositories. Cross-module queries go through service interfaces.

---

## Flyway Version Ranges

| Version Range | Owner Module        |
|---------------|---------------------|
| V1 – V19      | Platform Foundation |
| V20 – V39     | Auth Module         |
| V40 – V69     | Customer Module     |
| V70 – V89     | Vendor Module       |
| V90 – V109    | Delivery Module     |
| V110 – V129   | Admin Module        |
| V130 – V149   | Payment Module      |
| V150 – V169   | Notification Module |

**Never use a version number outside your module's range.**  
**Never delete or modify an applied migration.**

---

## API Contracts Reference

See **[docs/API_CONTRACTS.md](docs/API_CONTRACTS.md)** for:

- API URL prefix table (per module)
- Standard `ApiResponse<T>` response wrapper shape
- Pagination request parameters and `PageResponse<T>` shape
- `Authorization: Bearer <token>` header convention
- `X-Device-Id` header requirement for public routes
- Error code naming conventions

---

## Environment Variables

| Variable                     | Required | Default (local)                    | Description                              |
|------------------------------|----------|------------------------------------|------------------------------------------|
| `SPRING_PROFILES_ACTIVE`     | No       | `local`                            | Active Spring profile                    |
| `DB_URL`                     | Yes      | `jdbc:mysql://localhost:3306/...`  | Full JDBC connection URL                 |
| `DB_USERNAME`                | Yes      | `root`                             | MySQL username                           |
| `DB_PASSWORD`                | Yes      | `root`                             | MySQL password                           |
| `JWT_SECRET`                 | Yes      | (insecure default — change it!)    | Base64-encoded 256-bit HMAC key          |
| `JWT_ACCESS_EXPIRY_MS`       | No       | `900000` (15 min)                  | Access token TTL in milliseconds         |
| `JWT_REFRESH_EXPIRY_MS`      | No       | `604800000` (7 days)               | Refresh token TTL in milliseconds        |
| `FIREBASE_SERVICE_ACCOUNT_PATH` | No    | _(empty)_                          | Path to Firebase Admin SDK JSON file     |
| `SERVER_PORT`                | No       | `8080`                             | HTTP server port                         |

---

## Branch Strategy

```
main
└── develop
    ├── feature/platform-foundation  ← Phase 0 (merged first, others rebase after)
    ├── feature/auth-module          ← Phase 1
    ├── feature/customer-module
    ├── feature/vendor-module
    ├── feature/delivery-module
    ├── feature/admin-module
    ├── feature/payment-module
    └── feature/notification-module
```

**Rule:** All feature branches rebase onto `develop` after `feature/platform-foundation` is merged.
