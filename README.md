# Taskmaster Bot

A production-ready foundation for a Telegram task management bot, built with
Java 21, Spring Boot, PostgreSQL, Maven, Docker, and the TelegramBots API.
Business behavior is intentionally left unimplemented so the domain can evolve
without coupling it to infrastructure concerns.

## Technology

- Java 21
- Spring Boot 4.1
- Spring Data JPA and PostgreSQL
- Flyway database migrations
- TelegramBots long-polling Spring Boot starter
- Maven Wrapper
- Docker and Docker Compose
- Spring Boot Actuator

## Architecture

The codebase uses clear package boundaries:

```text
com.github.taskmasterbot
├── bot          Telegram update adapters
├── config       Framework and application configuration
├── controller   HTTP adapters
├── dto          Boundary data structures
├── entity       Persistence entities
├── exception    Application exception handling
├── mapper       Boundary/domain mapping
├── repository   Persistence ports
├── service      Application use cases
└── util         Focused shared utilities
```

Only configuration classes and the application entry point exist initially.
Each empty package contains package documentation to preserve the intended
structure in version control.

## Prerequisites

- Docker with Docker Compose, or
- Java 21 and a reachable PostgreSQL instance

Maven does not need to be installed; the included wrapper downloads the pinned
Maven version.

## Configuration

Copy the example environment file and replace placeholder credentials:

```bash
cp .env.example .env
```

Never commit `.env`. The application reads configuration from environment
variables and provides local-safe defaults for non-secret database settings.

## Run with Docker

```bash
docker compose up --build
```

PostgreSQL is exposed on port `5432` and the application on port `8080` by
default. Override these values in `.env`.

## Run locally

Start only PostgreSQL:

```bash
docker compose up -d postgres
```

Then run the application:

```bash
DB_PASSWORD=taskmaster ./mvnw spring-boot:run
```

For local development, the Compose default password is `taskmaster`. Use a
strong value in every deployed environment.

## Build and test

```bash
./mvnw clean verify
```

The executable artifact is created under `target/`.

## Health check

After startup:

```bash
curl http://localhost:8080/actuator/health
```

## Database migrations

Add versioned Flyway migrations to:

```text
src/main/resources/db/migration
```

Hibernate schema generation is disabled (`ddl-auto: validate`) so schema
changes remain explicit and reviewable.
