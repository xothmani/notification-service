# Notification Service

![Coverage](https://img.shields.io/badge/coverage-93%25-brightgreen)
![Tests](https://img.shields.io/badge/tests-171%20passing-brightgreen)
![Build](https://img.shields.io/badge/build-passing-brightgreen)

## Overview

Event-driven microservice for delivering real-time notifications to users.
Supports multiple channels: In-App, Email, SMS, Push.

## Tech Stack

- Java 21 + Spring Boot 3.3
- MongoDB
- Redis (queue + cache)
- SSE (Server-Sent Events)
- Virtual Threads
- Docker

## Getting Started

### Prerequisites

- Java 21
- Docker
- Maven

### Run Locally

**Step 1: Start Docker containers**
```bash
docker compose up -d
```

**Step 2: Start the application**
```bash
./mvnw spring-boot:run
```

**Step 3: Push a test notification**
```bash
docker exec -it notification-redis redis-cli
RPUSH notifications_queue '{...}'
```

### Environment Variables

| Variable         | Description                              | Default                          |
|------------------|------------------------------------------|----------------------------------|
| `INTERNAL_TOKEN` | Secret token for internal endpoints      | `dev-only-change-in-production`  |

## API Endpoints

| Method  | Path                                                  | Description                        |
|---------|-------------------------------------------------------|------------------------------------|
| `GET`   | `/api/notifications`                                  | Get paginated notifications         |
| `PATCH` | `/api/notifications/{id}/clicked`                     | Mark a notification as clicked      |
| `GET`   | `/api/stream/notifications`                           | Open SSE stream for real-time push  |
| `GET`   | `/api/internal/notifications/broadcast/{id}/stats`    | Broadcast delivery stats (internal) |


## Architecture

```
Producer
   │
   ▼
Redis Queue ──► Worker ──► MongoDB
                  │
                  ├──► Cache-Aside (Redis)
                  │
                  ├──► SSE (real-time push)
                  │
                  └──► Dead Letter Queue (on failure)
```
