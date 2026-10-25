# 🎬 Movie Service (`movie-service`)

[![Java](https://img.shields.io/badge/Java-21-orange.svg)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1.1-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-17-blue.svg)](https://www.postgresql.org/)
[![Redis](https://img.shields.io/badge/Redis-7%20(LRU)-red.svg)](https://redis.io/)
[![Apache Kafka](https://img.shields.io/badge/Apache%20Kafka-3.8.0%20(KRaft)-black.svg)](https://kafka.apache.org/)
[![TMDB API](https://img.shields.io/badge/TMDB%20API-v3%20%7C%20v4-01d277.svg)](https://developer.themoviedb.org/docs)

A high-performance, enterprise-grade **Movie Catalog, Personalization & Home Feed Microservice** built with **Java 21**, **Spring Boot**, **PostgreSQL 17**, **Redis 7 (LRU)**, and **Apache Kafka**.

Designed with **3-Tier Multi-Level Read-Through Caching**, **Singleflight Leader Mutex** for dog-pile prevention, **Proactive Background Pre-Warming**, **Transactional Outbox Event Streaming** for real-time recommendation updates, and a **50-Movie Waterfall Backfill Engine** guaranteeing exactly 50 personalized movie recommendations at any given stage.

> 📖 **Team Integration Guides:**
> - For full API schemas, UI component blueprints, Kafka event payloads, and ML model contracts, refer to [**PROJECT_CONTEXT.md**](file:///Users/saivamshi/projects/movie-service/PROJECT_CONTEXT.md).

---

## 📑 Table of Contents
- [Architecture Overview](#-architecture-overview)
- [Key Features](#-key-features)
- [Tech Stack](#-tech-stack)
- [API Quick Reference & Curls](#-api-quick-reference--curls)
- [Docker Compose & Quickstart](#-docker-compose--quickstart)
- [Configuration & Environment Variables](#-configuration--environment-variables)
- [Automated Tests](#-automated-tests)

---

## 🏛️ Architecture Overview

```
                                  ┌─────────────────────────────┐
                                  │    API Gateway / Clients    │
                                  └──────────────┬──────────────┘
                                                 │
                        ┌────────────────────────┴────────────────────────┐
                        ▼                                                 ▼
             ┌─────────────────────┐                           ┌─────────────────────┐
             │    user-service     │                           │    movie-service    │
             │       (:8081)       │                           │       (:8082)       │
             └──────────┬──────────┘                           └──────────┬──────────┘
                        │                                                 │
                        │ Issues JWT (HMAC-SHA256)                        │ Reads & verifies JWT
                        └────────────────────────┬────────────────────────┘
                                                │
                 ┌──────────────────────────────┼──────────────────────────────┐
                 ▼                              ▼                              ▼
          ┌─────────────┐                ┌─────────────┐                ┌─────────────┐
          │ PostgreSQL  │                │   Redis 7   │                │   Apache    │
          │     17      │                │ (LRU Cache) │                │    Kafka    │
          │             │                │             │                │             │
          │• preferences│                │• feeds      │                │• ratings    │
          │• watchlists │                │• details    │                │  (outbox)   │
          │• reviews    │                │• search     │                └──────┬──────┘
          │• outbox     │                └─────────────┘                       │
          └─────────────┘                                                      │ Consumer
                                                                               ▼
                                                                     ┌─────────────────────┐
                                                                     │recommendation-serv. │
                                                                     │       (:8083)       │
                                                                     └─────────────────────┘
```

---

## ✨ Key Features

### 🏠 5 Dedicated Home Feed Sections
- **Trending Today**: `/api/v1/movies/home/trending-today` (Daily buzz & momentum within the last 6 months)
- **Trending This Week**: `/api/v1/movies/home/trending-week` (Steady weekly trending titles within the last 2 years)
- **Popular Movies**: `/api/v1/movies/home/popular` (All-time timeless mega-blockbusters across years)
- **Top Rated Classics**: `/api/v1/movies/home/top-rated` (Critically acclaimed films with minimum 50 votes)
- **Now Playing**: `/api/v1/movies/home/now-playing` (Theatrical release window: -6 weeks to +1 week)
- **Unified Feed**: `/api/v1/movies/home` (Aggregated Page 1 payload for instant app boot)

### 👥 User Preference & Composite Personalization Engine
- **Supported Cinema Industries**: `HOLLYWOOD` (en), `TOLLYWOOD` (te), `BOLLYWOOD` (hi), `MOLLYWOOD` (ml), `KOLLYWOOD` (ta).
- **Supported Core Genres**: `ACTION`, `ADVENTURE`, `THRILLER`, `HORROR`, `DRAMA`, `COMEDY`, `CRIME`, `DOCUMENTARY`, `FAMILY`, `ROMANCE`, `SCIENCE_FICTION`.
- **Atomic Caching & Diversity Shuffle**: Caches atomic building blocks in Redis; dynamically blends and shuffles candidate pools with diversity constraints for logged-in users while serving global defaults to guests.

### ⚡ Resilient 3-Tier Multi-Level Caching & Concurrency Shields
- **Tier 1 (Redis)**: 1-day TTL with `allkeys-lru` eviction policy.
- **Tier 2 (PostgreSQL)**: Permanent storage populated asynchronously.
- **Tier 3 (TMDB API v3 / v4)**: Immediate user response + non-blocking write-behind.
- **Proactive Pre-Warming**: Background worker warms top trending movie details with 250ms throttle before users click.
- **Singleflight Leader Mutex**: Distributed `SETNX` lock prevents dog-piling / cache stampedes on cold misses.

### 🌟 Decoupled User Actions & Event Streaming
- **1-to-5 Star Reviews & Ratings**: Atomic platform rating calculation + Transactional Outbox.
- **High-Velocity Watchlist**: Zero foreign-key locking; on-demand hydration.
- **Kafka Rating Streaming**: Publishes `movie.rating.v1` events partitioned by `userId`.

### 🤖 50-Movie Recommendation Waterfall Engine
- Consumes `recommendation-service` generic predictions.
- **Waterfall Backfill**: If ML predictions are fewer than 50 (or during cold-starts/downtime), automatically backfills the deficit with top-rated movies matching the user's preferred industry and genres (descending by rating). Always guarantees **exactly 50 movies**!

---

## 📡 API Quick Reference & Curls

### 1. Home Feed Endpoints

#### Global Feeds
```bash
# Trending Today
curl -X GET "http://localhost:8082/api/v1/movies/home/trending-today?page=1"

# Trending This Week
curl -X GET "http://localhost:8082/api/v1/movies/home/trending-week?page=1"

# All-Time Popular
curl -X GET "http://localhost:8082/api/v1/movies/home/popular?page=1"

# Top Rated
curl -X GET "http://localhost:8082/api/v1/movies/home/top-rated?page=1"

# Now Playing in Theatres
curl -X GET "http://localhost:8082/api/v1/movies/home/now-playing?page=1"

# Aggregated All-in-One Feed
curl -X GET "http://localhost:8082/api/v1/movies/home"
```

#### Industry & Genre Targeted Feeds
```bash
# Tollywood (Telugu) Trending Today (Last 6 Months)
curl -X GET "http://localhost:8082/api/v1/movies/home/trending-today?industry=TOLLYWOOD&page=1"

# Tollywood (Telugu) Trending This Week (Last 2 Years)
curl -X GET "http://localhost:8082/api/v1/movies/home/trending-week?industry=TOLLYWOOD&page=1"

# Tollywood All-Time Popular Action Hits
curl -X GET "http://localhost:8082/api/v1/movies/home/popular?industry=TOLLYWOOD&genre=ACTION&page=1"

# Bollywood (Hindi) Top Rated
curl -X GET "http://localhost:8082/api/v1/movies/home/top-rated?industry=BOLLYWOOD&page=1"
```

### 2. Catalog & Multi-Search
```bash
# Movie Details (Full Cast & Crew)
curl -X GET "http://localhost:8082/api/v1/movies/550"

# Actor Profile & Filmography
curl -X GET "http://localhost:8082/api/v1/movies/actors/287"

# Director Profile & Filmography
curl -X GET "http://localhost:8082/api/v1/movies/crew/7467"

# Fast Multi-Search (Movies, Actors, Crew)
curl -X GET "http://localhost:8082/api/v1/movies/search?query=nolan&page=1"
```

### 3. User Preferences (Requires JWT)
```bash
# Save Preferences
curl -X PUT "http://localhost:8082/api/v1/movies/preferences" \
  -H "Authorization: Bearer <JWT_TOKEN>" \
  -H "Content-Type: application/json" \
  -d '{
    "industries": ["TOLLYWOOD", "HOLLYWOOD"],
    "genres": ["ACTION", "THRILLER", "SCIENCE_FICTION"]
  }'

# Get My Preferences
curl -X GET "http://localhost:8082/api/v1/movies/preferences/me" \
  -H "Authorization: Bearer <JWT_TOKEN>"
```

### 4. Watchlist (Requires JWT)
```bash
# Add Movie to Watchlist
curl -X POST "http://localhost:8082/api/v1/movies/1011985/watchlist" \
  -H "Authorization: Bearer <JWT_TOKEN>"

# Check Watchlist Status (true/false)
curl -X GET "http://localhost:8082/api/v1/movies/1011985/watchlist/status" \
  -H "Authorization: Bearer <JWT_TOKEN>"

# Remove from Watchlist
curl -X DELETE "http://localhost:8082/api/v1/movies/1011985/watchlist" \
  -H "Authorization: Bearer <JWT_TOKEN>"

# Get My Watchlist
curl -X GET "http://localhost:8082/api/v1/movies/watchlist/me?page=0&size=20" \
  -H "Authorization: Bearer <JWT_TOKEN>"
```

### 5. Reviews & Ratings
```bash
# Submit / Update 1-to-5 Star Review (Requires JWT)
curl -X POST "http://localhost:8082/api/v1/movies/1011985/reviews" \
  -H "Authorization: Bearer <JWT_TOKEN>" \
  -H "Content-Type: application/json" \
  -d '{
    "rating": 4.5,
    "reviewText": "Stunning visuals and phenomenal sound design!"
  }'

# Read Reviews for a Movie (Public)
curl -X GET "http://localhost:8082/api/v1/movies/1011985/reviews?page=0&size=10"

# Read Reviews Written by Authenticated User (Requires JWT)
curl -X GET "http://localhost:8082/api/v1/movies/reviews/me?page=0&size=10" \
  -H "Authorization: Bearer <JWT_TOKEN>"

# Read Reviews Written by Specific User (Public Profile)
curl -X GET "http://localhost:8082/api/v1/movies/reviews/users/3fa85f64-5717-4562-b3fc-2c963f66afa6?page=0&size=10"
```

### 6. Personalized Recommendations (Waterfall Backfilled)
```bash
curl -X GET "http://localhost:8082/api/v1/movies/recommendations" \
  -H "Authorization: Bearer <JWT_TOKEN>"
```

---

## 🐳 Docker Compose & Quickstart

The repository includes a complete [`docker-compose.yml`](file:///Users/saivamshi/projects/movie-service/docker-compose.yml) file spinning up:
- **PostgreSQL 17** (`movieServiceDB`) on port `5432`
- **Redis 7** (LRU Eviction) on port `6379`
- **Apache Kafka** (KRaft Mode) on port `9092`
- **Kafka Topic Initializer** (Creates `movie.rating.v1` with 3 partitions)
- **Movie Service Application** (Java 21 / Spring Boot) on port `8082`

### Run Complete Stack
```bash
docker compose up -d --build
```

### Verify Status
```bash
docker compose ps
```

### Check Logs
```bash
docker compose logs -f movie-service
```

### Stop Stack
```bash
docker compose down
```

---

## ⚙️ Configuration & Environment Variables

| Variable | Default Value | Description |
|---|---|---|
| `SERVER_PORT` | `8082` | HTTP port |
| `DB_URL` | `jdbc:postgresql://postgres:5432/movieServiceDB` | PostgreSQL JDBC connection URL |
| `DB_USERNAME` | `postgres` | Database username |
| `DB_PASSWORD` | `password` | Database password |
| `REDIS_HOST` | `redis` | Redis host |
| `REDIS_PORT` | `6379` | Redis port |
| `JWT_SECRET` | *(Shared HMAC-SHA256 secret)* | Shared secret with `user-service` |
| `GATEWAY_SHARED_SECRET` | `local-dev-gateway-secret` | Anti-spoofing gateway secret |
| `TMDB_BASE_URL` | `https://api.themoviedb.org/3` | TMDB API Base URL |
| `TMDB_BEARER_TOKEN` | *(Configured Bearer Read Token)* | TMDB Authorization Header |
| `KAFKA_BOOTSTRAP_SERVERS`| `kafka:29092` | Kafka cluster broker endpoint |
| `KAFKA_TOPIC_MOVIE_RATING`| `movie.rating.v1` | Kafka rating events topic |
| `RECOMMENDATION_SERVICE_URL` | `http://recommendation-service:8083` | Downstream ML recommendation service URL |
| `PREWARM_ENABLED` | `true` | Enable background trending pre-warming |
| `PREWARM_TOP_MOVIES_LIMIT` | `10` | Number of trending items to pre-warm |
| `PREWARM_THROTTLE_DELAY_MS`| `250` | Throttle delay between prewarm calls |

---

## 🧪 Automated Tests

The service includes comprehensive unit, integration, and live external tests:

```bash
# Run complete test suite (54/54 passing)
./mvnw test

# Run Home Feed tests
./mvnw test -Dtest=HomeFeedServiceTest

# Run Catalog & Mock tests
./mvnw test -Dtest=CatalogServiceTest

# Run Live TMDB External Connectivity Test
./mvnw test -Dtest=TmdbExternalLiveTest
```
