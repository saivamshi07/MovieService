# 🎬 Movie Service (`movie-service`)

[![Java](https://img.shields.io/badge/Java-21-orange.svg)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1.1-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-17-blue.svg)](https://www.postgresql.org/)
[![Redis](https://img.shields.io/badge/Redis-7%20(LRU)-red.svg)](https://redis.io/)
[![Apache Kafka](https://img.shields.io/badge/Apache%20Kafka-3.8.0%20(KRaft)-black.svg)](https://kafka.apache.org/)
[![TMDB API](https://img.shields.io/badge/TMDB%20API-v3%20%7C%20v4-01d277.svg)](https://developer.themoviedb.org/docs)

A high-performance, enterprise-grade **Movie Catalog, Personalization & Home Feed Microservice** built with **Java 21**, **Spring Boot**, **PostgreSQL 17**, **Redis 7 (LRU)**, and **Apache Kafka**.

Designed with **3-Tier Multi-Level Read-Through Caching**, **Singleflight Leader Mutex** for dog-pile prevention, **Proactive Background Pre-Warming**, **Transactional Outbox Event Streaming** for real-time rating updates, **Privacy-Controlled Social Access** (Public vs. Follower-only), **Dynamic TMDB Genre Resolution**, and a **Personalized Recommendation Engine** targeting top curated movies based on user-preferred industries and genres.

> 📖 **Team Integration Guides:**
> - For full API schemas, UI component blueprints, Kafka event payloads, and privacy contracts, refer to [**PROJECT_CONTEXT.md**](file:///Users/saivamshi/projects/movie-service/PROJECT_CONTEXT.md).

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
                        │ Issues JWT & verifies                           │ Reads & verifies JWT
                        │ privacy graph (Neo4j / Redis)                   │ Enforces privacy access
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
          │• outbox     │                │• genres     │                       │
          └─────────────┘                └─────────────┘                       │ Consumer
                                                                               ▼
                                                                     ┌─────────────────────┐
                                                                     │recommendation-serv. │
                                                                     │       (:8083)       │
                                                                     └─────────────────────┘
```

---

## ✨ Key Features

### 🏠 5 Dedicated Home Feed Sections
- **Trending Today**: `/api/v1/movies/home/trending-today` (Released in **last 4 weeks up to today + next 7 days**, sorted strictly by `vote_count.desc`, tie-broken by `popularity.desc`).
- **Trending This Week**: `/api/v1/movies/home/trending-week` (Released in **last 3 months up to today + next 7 days**, sorted strictly by `vote_count.desc`, tie-broken by `popularity.desc`).
- **Popular Movies**: `/api/v1/movies/home/popular` (Hits TMDB with `sort_by=vote_count.desc` without date filter, returning results sorted by `vote_count.desc`, tie-broken by `popularity.desc`).
- **Top Rated Classics**: `/api/v1/movies/home/top-rated` (Critically acclaimed films sorted by `vote_count.desc`, no release date filter; dynamic thresholds calculated from page 1: `vote_count >= last result of page 1 / 2` and `vote_average >= highest vote average of page 1 - 2.0`; multi-page fetch ensures at least 20 items).
- **Now Playing**: `/api/v1/movies/home/now-playing` (Theatrical release window: last 3 weeks up to today, sorted by `vote_count.desc`, tie-broken by `popularity.desc`).
- **Unified Feed**: `/api/v1/movies/home` (Aggregated Page 1 payload for instant app boot).

### ⚡ Top 20 Popular Movies API with Cache Miss Fallback
- **Endpoint**: `/api/v1/movies/popular?industry={industry}&genre={genre}&excludeMovieId={id}` (and `/api/v1/movies/home/popular/top`).
- **Rapid Cache-First Delivery**: Reads directly from pre-warmed Redis pools (`feed:pool:POPULAR:ind:{industry}:genre:{genre}`).
- **Dynamic On-Demand Fallback**: On cache miss, fetches on-demand from TMDB discover (sorted by `vote_count.desc`) and warms Redis asynchronously in the background.
- **Exclusion Support**: Allows excluding the currently viewed movie ID (`excludeMovieId`).

### 📦 Multi-Tier Batch Movie Hydration
- **Endpoint**: `POST /api/v1/movies/batch` with `{"movieIds": [...]}`.
- **Cascading 3-Tier Resolution**:
  1. Checks Redis cache (`movie:detail:{id}`).
  2. Resolves misses against PostgreSQL DB (`MovieRepository`).
  3. Resolves remaining misses from TMDB API (`/movie/{id}`), persisting them to PostgreSQL and caching in Redis.
- Preserves the exact requested ID ordering in the final response.

### 🌐 Dynamic TMDB Genre Resolution & In-Memory Caching
- **Bidirectional In-Memory Cache**: Thread-safe resolution between TMDB numeric genre IDs and genre display names.
- **Dynamic API Fetch**: Hitting TMDB `/genre/movie/list` on cache miss to discover and register new genres automatically.
- **Full TMDB Standard Genre Support**: All 19 standard TMDB genres mapped (`ACTION`, `ADVENTURE`, `ANIMATION`, `COMEDY`, `CRIME`, `DOCUMENTARY`, `DRAMA`, `FAMILY`, `FANTASY`, `HISTORY`, `HORROR`, `MUSIC`, `MYSTERY`, `ROMANCE`, `SCIENCE_FICTION`, `TV_MOVIE`, `THRILLER`, `WAR`, `WESTERN`) plus alias normalization (`SCI_FI` / `SCIFI` $\rightarrow$ `SCIENCE_FICTION`).
- **Automatic Parameter Translation**: Comma/pipe-separated genre names in query params automatically map to numeric TMDB genre IDs.

### 🔒 Social Privacy-Controlled Access (Reviews & Watchlist)
- **Granular Privacy Enforcement**: Integrates with user social graph state:
  - **Public Profiles**: Reviews and watchlists are visible to all users.
  - **Private Profiles**: Accessible only to confirmed followers or the owner themselves.
  - **Restricted Access Response**: Unauthorized requests return `403 Forbidden`.
  - **Movie Reviews Feed Filtering**: In public movie review feeds (`/api/v1/movies/{id}/reviews`), reviews written by inaccessible private users are skipped automatically.

### ⭐ Enhanced Reviews, Sorting & Atomic Rating Sync
- **Pagination & Multi-Mode Sorting**: All review endpoints (`/reviews`, `/reviews/me`, `/reviews/users/{userId}`) support Spring Data `Pageable` (`page`, `size`) and dynamic sorting:
  - `sort=latest`: Sorts by newest first (`createdAt` desc).
  - `sort=top`: Sorts by highest star rating (`rating` desc).
  - `sort=low`: Sorts by lowest star rating (`rating` asc).
- **Atomic Cache Synchronization**: Updating a review recalculates the movie platform average and immediately updates both PostgreSQL and Redis cached movie details (`movie:detail:{id}`).
- **Transactional Outbox**: Automatically publishes `movie.rating.v1` events to Kafka.

### 🎯 20-Movie Personalized Recommendations Engine
- **Endpoint**: `/api/v1/movies/recommendations` (Targeting 20 curated recommendations).
- **Preference Discovery**: Queries TMDB discover for the user's preferred industries and genres sorted by `vote_count.desc` (top 50 candidate pool).
- **User Activity Filtering**: Filters out movies already rated/reviewed by the user.
- **Quality Re-ranking**: Sorts unrated candidate movies by `vote_average.desc` to return the top 20 personalized recommendations.

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

# Top Rated Classics
curl -X GET "http://localhost:8082/api/v1/movies/home/top-rated?page=1"

# Now Playing in Theatres
curl -X GET "http://localhost:8082/api/v1/movies/home/now-playing?page=1"

# Aggregated All-in-One Feed
curl -X GET "http://localhost:8082/api/v1/movies/home"
```

#### Industry & Genre Targeted Feeds
```bash
# Tollywood Trending Today (vote_count.desc)
curl -X GET "http://localhost:8082/api/v1/movies/home/trending-today?industry=TOLLYWOOD&page=1"

# Tollywood All-Time Popular Action Hits
curl -X GET "http://localhost:8082/api/v1/movies/home/popular?industry=TOLLYWOOD&genre=ACTION&page=1"

# Top 20 Popular Movies for Industry & Genre with Movie Exclusion
curl -X GET "http://localhost:8082/api/v1/movies/popular?industry=TOLLYWOOD&genre=ACTION&excludeMovieId=1011985"
```

### 2. Catalog, Search & Batch Hydration
```bash
# Movie Details (Full Cast & Crew)
curl -X GET "http://localhost:8082/api/v1/movies/550"

# Batch Movie Hydration (Resolves via Redis -> DB -> TMDB)
curl -X POST "http://localhost:8082/api/v1/movies/batch" \
  -H "Content-Type: application/json" \
  -d '{"movieIds": [550, 1011985, 157336]}'

# Actor Profile & Filmography
curl -X GET "http://localhost:8082/api/v1/movies/actors/287"

# Director Profile & Filmography
curl -X GET "http://localhost:8082/api/v1/movies/crew/7467"

# Multi-Search (Movies, Actors, Crew)
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

# Get All Available Preference Options (Industries & 19 TMDB Genres)
curl -X GET "http://localhost:8082/api/v1/movies/preferences/options"
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

# Get My Paginated Watchlist
curl -X GET "http://localhost:8082/api/v1/movies/watchlist/me?page=0&size=20" \
  -H "Authorization: Bearer <JWT_TOKEN>"

# Get Another User's Watchlist (Privacy Enforced - 403 Forbidden if Private & Not Following)
curl -X GET "http://localhost:8082/api/v1/movies/watchlist/users/3fa85f64-5717-4562-b3fc-2c963f66afa6?page=0&size=20" \
  -H "Authorization: Bearer <JWT_TOKEN>"
```

### 5. Reviews & Ratings
```bash
# Submit / Update Review (Requires JWT)
curl -X POST "http://localhost:8082/api/v1/movies/1011985/reviews" \
  -H "Authorization: Bearer <JWT_TOKEN>" \
  -H "Content-Type: application/json" \
  -d '{
    "rating": 4.5,
    "reviewText": "Stunning visuals and phenomenal sound design!"
  }'

# Read Reviews for a Movie (Supports sort=latest | top | low & pagination; skips inaccessible private users)
curl -X GET "http://localhost:8082/api/v1/movies/1011985/reviews?page=0&size=10&sort=top"

# Read Reviews Written by Authenticated User (Supports sort=latest | top | low)
curl -X GET "http://localhost:8082/api/v1/movies/reviews/me?page=0&size=10&sort=latest" \
  -H "Authorization: Bearer <JWT_TOKEN>"

# Read Reviews Written by Specific User (Privacy Enforced - 403 Forbidden if Private & Not Following)
curl -X GET "http://localhost:8082/api/v1/movies/reviews/users/3fa85f64-5717-4562-b3fc-2c963f66afa6?page=0&size=10&sort=latest" \
  -H "Authorization: Bearer <JWT_TOKEN>"
```

### 6. Personalized Recommendations (Target 20)
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
| `TMDB_CONNECT_TIMEOUT_MS` | `10000` | TMDB HTTP connection timeout (10s) |
| `TMDB_READ_TIMEOUT_MS` | `15000` | TMDB HTTP read timeout (15s) |
| `FEED_WARMUP_ENABLED` | `true` | Enable async startup feed warming |
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
# Run complete test suite (127/127 passing)
./mvnw test

# Run Home Feed tests
./mvnw test -Dtest=HomeFeedServiceTest

# Run Catalog & Mock tests
./mvnw test -Dtest=CatalogServiceTest

# Run Review & Privacy tests
./mvnw test -Dtest=ReviewServiceTest,UserReviewControllerTest

# Run Live TMDB External Connectivity Test
./mvnw test -Dtest=TmdbExternalLiveTest
```
