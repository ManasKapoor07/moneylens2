# MoneyLens Backend — Phone OTP Auth

Spring Boot 3 + Java 21 + PostgreSQL + Twilio + JWT (no Lombok)

---

## Quick Start

### 1. Create database
```sql
CREATE DATABASE moneylens;
```

### 2. Set environment variables
```env
DB_USER=postgres
DB_PASS=your_password
JWT_SECRET=moneylens-super-secret-key-min-32-chars
TWILIO_ACCOUNT_SID=ACxxxxxxxxxx
TWILIO_AUTH_TOKEN=your_token
TWILIO_PHONE_NUMBER=+1xxxxxxxxxx
```

### 3. Run

Production (real SMS):
```bash
mvn spring-boot:run
```

Local dev (OTP printed in console, no Twilio needed):
```bash
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

---

## API

### Send OTP
```
POST /api/auth/send-otp
{ "phoneNumber": "+919876543210" }
```

### Verify OTP → get JWT
```
POST /api/auth/verify-otp
{ "phoneNumber": "+919876543210", "otp": "123456" }
```

### Get current user (protected)
```
GET /api/auth/me
Authorization: Bearer <token>
```
