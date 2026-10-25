# 🎬 Movie Service — System Context & Integration Architecture

This document serves as the **definitive technical specification and integration guide** for:
- 💻 **Frontend Engineers** (Web / Next.js / React, Mobile / React Native / Flutter)
- 🧠 **Recommendation Service Engineers** (`recommendation-service` ML pipelines)
- 🔐 **User & Auth Service Engineers** (`user-service` and API Gateway)
- 🛠️ **DevOps & Platform Engineers**

---

## 📑 Table of Contents
1. [System Architecture & Microservice Boundaries](#1-system-architecture--microservice-boundaries)
2. [Frontend Integration Guide](#2-frontend-integration-guide)
   - [Authentication & Request Headers](#authentication--request-headers)
   - [Home Feed Sections & Filtering](#home-feed-sections--filtering)
   - [Movie, Actor & Crew Details](#movie-actor--crew-details)
   - [Unified Multi-Search](#unified-multi-search)
   - [Watchlist State & Actions](#watchlist-state--actions)
   - [Reviews & 5-Star Ratings](#reviews--5-star-ratings)
   - [Image CDN & Poster URL Construction](#image-cdn--poster-url-construction)
   - [Standard Error Handling & Status Codes](#standard-error-handling--status-codes)
3. [Recommendation Service Integration Guide](#3-recommendation-service-integration-guide)
   - [Kafka Event Streaming: `movie.rating.v1`](#kafka-event-streaming-movieratingv1)
   - [REST Inbound Contract: Model Predictions](#rest-inbound-contract-model-predictions)
   - [50-Movie Waterfall Fallback Guarantee](#50-movie-waterfall-fallback-guarantee)
4. [User Service & API Gateway Contract](#4-user-service--api-gateway-contract)
5. [Caching, Data Stores & Operational Engine](#5-caching-data-stores--operational-engine)
   - [Redis Key Conventions & Eviction](#redis-key-conventions--eviction)
   - [Singleflight Leader Mutex (Stampede Guard)](#singleflight-leader-mutex-stampede-guard)
   - [Proactive Background Pre-Warming](#proactive-background-pre-warming)
   - [Scheduled Refresh Cron Matrix](#scheduled-refresh-cron-matrix)

---

## 1. System Architecture & Microservice Boundaries

```mermaid
flowchart TD
    Client["Clients (Web / iOS / Android)"]
    Gateway["API Gateway (:8080)"]
    UserService["User Service (:8081)<br/>• Auth / JWT / Profiles"]
    MovieService["Movie Service (:8082)<br/>• Catalog / Feeds / Details<br/>• Watchlist / Reviews / Preferences<br/>• 3-Tier Multi-Level Cache<br/>• Singleflight Mutex Guard"]
    RecService["Recommendation Service (:8083)<br/>• Collaborative Filtering<br/>• Vector Similarity Models"]
    Postgres[("PostgreSQL 17<br/>• Permanent Catalog<br/>• Watchlists & Reviews<br/>• Transactional Outbox")]
    Redis[("Redis 7 (LRU)<br/>• Section Feeds<br/>• Candidate Pools<br/>• Detail Caches")]
    Kafka{"Apache Kafka<br/>• topic: movie.rating.v1"}
    TMDB["TMDB API v3 / v4<br/>• Upstream Global Catalog"]

    Client -->|HTTP / JSON| Gateway
    Gateway -->|Validate Token| UserService
    Gateway -->|Proxy Requests| MovieService
    
    MovieService -->|Query / Mutex| Redis
    MovieService -->|ACID Persistence| Postgres
    MovieService -->|Cold Miss Fallback| TMDB
    MovieService -->|Publish Ratings via Outbox| Kafka
    Kafka -->|Consume Rating Events| RecService
    MovieService -->|Fetch ML Candidate IDs| RecService
```

### Responsibility Matrix
| Domain | `movie-service` Responsibility | Other Service Responsibility |
| :--- | :--- | :--- |
| **User Identity** | Validates JWT signatures and parses `userId`. | `user-service` manages passwords, sessions, login/signup. |
| **Movie Metadata** | Caches, indexes, enriches, and serves movie, actor, crew details. | TMDB provides raw external global movie data. |
| **Feeds** | Serves 5 dynamic sections, cached candidate pools, and diversity shuffles. | Client renders carousels and paginated grids. |
| **User Interactions**| Persists watchlists, reviews, and platform ratings. | Publishes to Kafka for downstream analytics. |
| **Recommendations** | Calls ML service, handles timeouts/cold starts, guarantees **exactly 50 items**. | `recommendation-service` computes personalized ML predictions. |

---

## 2. Frontend Integration Guide

### Authentication & Request Headers
All private endpoints require a Bearer token issued by `user-service`:

```http
Authorization: Bearer eyJhbGciOiJIUzI1NiJ9...
Content-Type: application/json
Accept: application/json
```

If the API Gateway terminates authentication, it injects upstream headers:
- `X-User-Id`: UUID string (e.g., `3fa85f64-5717-4562-b3fc-2c963f66afa6`)
- `X-User-Role`: User role string (e.g., `ROLE_USER`)
- `X-Gateway-Secret`: Configured shared gateway secret to prevent spoofing.

---

### Home Feed Sections & Filtering
The home feed features **5 dedicated sections** available individually (with pagination) or aggregated in a single request.

#### Supported Industries (`industry` param)
- `ALL` (Global across all languages)
- `HOLLYWOOD` (English - `en`)
- `TOLLYWOOD` (Telugu - `te`)
- `BOLLYWOOD` (Hindi - `hi`)
- `MOLLYWOOD` (Malayalam - `ml`)
- `KOLLYWOOD` (Tamil - `ta`)

#### Supported Core Genres (`genre` param)
`ACTION`, `ADVENTURE`, `THRILLER`, `HORROR`, `DRAMA`, `COMEDY`, `CRIME`, `DOCUMENTARY`, `FAMILY`, `ROMANCE`, `SCIENCE_FICTION`

#### Feed Endpoints
```http
# 1. Trending Today (Daily high-momentum releases - last 6 months)
GET /api/v1/movies/home/trending-today?industry=TOLLYWOOD&page=1

# 2. Trending This Week (Steady weekly momentum - last 2 years)
GET /api/v1/movies/home/trending-week?industry=TOLLYWOOD&page=1

# 3. All-Time Popular (Timeless mega-blockbusters)
GET /api/v1/movies/home/popular?industry=TOLLYWOOD&genre=ACTION&page=1

# 4. Top Rated Classics (Highest community rating with min 50 votes)
GET /api/v1/movies/home/top-rated?industry=HOLLYWOOD&page=1

# 5. Now Playing (Theatrical release window: -6 weeks to +1 week)
GET /api/v1/movies/home/now-playing?industry=BOLLYWOOD&page=1

# 6. Aggregated Feed (All 5 sections on Page 1 for Initial Home Screen Load)
GET /api/v1/movies/home
```

#### Sample Feed Response (`SectionResponseDTO`)
```json
{
  "section": "TRENDING_TODAY",
  "title": "Trending Today - TOLLYWOOD",
  "page": 1,
  "totalPages": 5,
  "totalResults": 100,
  "movies": [
    {
      "id": 1011985,
      "title": "Pushpa 2: The Rule",
      "overview": "Pushpa Raj continues to consolidate his grip over the red sandalwood syndicate...",
      "posterUrl": "https://image.tmdb.org/t/p/w500/pushpa2.jpg",
      "backdropUrl": "https://image.tmdb.org/t/p/w1280/pushpa2_bg.jpg",
      "releaseDate": "2024-12-05",
      "releaseYear": 2024,
      "originalLanguage": "te",
      "industry": "TOLLYWOOD",
      "genres": ["Action", "Thriller", "Drama"],
      "tmdbVoteAverage": 8.1,
      "platformRating": 4.8,
      "platformRatingCount": 352
    }
  ]
}
```

---

### Movie, Actor & Crew Details

#### 1. Movie Details (Full Cast, Crew, Directors, Overview)
```http
GET /api/v1/movies/{id}
```
**Response (`MovieDetailDTO`):**
```json
{
  "id": 550,
  "title": "Fight Club",
  "tagline": "Mischief. Mayhem. Soap.",
  "overview": "An insomniac office worker and a devil-may-care soap maker form an underground fight club...",
  "posterUrl": "https://image.tmdb.org/t/p/w500/pB8BM7pdSp6B6Ih7QZ4DrQ3PmJK.jpg",
  "backdropUrl": "https://image.tmdb.org/t/p/original/hZkgoQYus5vegHoetLkCJzb17zJ.jpg",
  "releaseDate": "1999-10-15",
  "releaseYear": 1999,
  "runtime": 139,
  "originalLanguage": "en",
  "industry": "HOLLYWOOD",
  "status": "Released",
  "tmdbVoteAverage": 8.43,
  "platformRating": 4.7,
  "platformRatingCount": 1820,
  "genres": ["Drama", "Thriller"],
  "directors": ["David Fincher"],
  "writers": ["Chuck Palahniuk", "Jim Uhls"],
  "cast": [
    {
      "id": 287,
      "name": "Brad Pitt",
      "character": "Tyler Durden",
      "profileUrl": "https://image.tmdb.org/t/p/w185/cckcYc2v0yh1tc9QjRelptsqpHW.jpg",
      "order": 1
    }
  ],
  "crew": [
    {
      "id": 7467,
      "name": "David Fincher",
      "department": "Directing",
      "job": "Director",
      "profileUrl": "https://image.tmdb.org/t/p/w185/director.jpg"
    }
  ]
}
```

#### 2. Actor Details (Filmography & Biography)
```http
GET /api/v1/movies/actors/{personId}
```

#### 3. Crew Details (Designations & Filmography)
```http
GET /api/v1/movies/crew/{personId}
```

---

### Unified Multi-Search
Fast multi-entity search querying Movies, Actors, and Crew simultaneously with 1-day Redis caching.
```http
GET /api/v1/movies/search?query=christopher+nolan&page=1
```
**Response Format:**
```json
{
  "page": 1,
  "totalPages": 1,
  "totalResults": 15,
  "results": [
    {
      "id": 525,
      "mediaType": "person",
      "name": "Christopher Nolan",
      "knownForDepartment": "Directing",
      "profileUrl": "https://image.tmdb.org/t/p/w185/nolan.jpg",
      "knownFor": [
        {
          "id": 157336,
          "title": "Interstellar",
          "mediaType": "movie"
        }
      ]
    }
  ]
}
```

---

### Watchlist State & Actions

#### Check Status (Used for Bookmark Icon on Movie Detail Page)
```http
GET /api/v1/movies/{movieId}/watchlist/status
Authorization: Bearer <token>
```
**Response:**
```json
{
  "inWatchlist": true
}
```

#### Add to Watchlist
```http
POST /api/v1/movies/{movieId}/watchlist
Authorization: Bearer <token>
```
*(Status: `201 Created` or `200 OK`)*

#### Remove from Watchlist
```http
DELETE /api/v1/movies/{movieId}/watchlist
Authorization: Bearer <token>
```
*(Status: `204 No Content`)*

#### Get User's Paginated Watchlist (Hydrated Movie Cards)
```http
GET /api/v1/movies/watchlist/me?page=0&size=20
Authorization: Bearer <token>
```

---

### Reviews & 5-Star Ratings

#### Rules & Constraints
- Ratings are numeric from `1.0` to `5.0` (typically half-star increments: `1.0, 1.5, 2.0, 2.5, 3.0, 3.5, 4.0, 4.5, 5.0`).
- Maximum review text length: 2,000 characters.
- One review per user per movie (subsequent POSTs update existing review atomically).
- Automatically triggers a Transactional Outbox event published to Kafka.

#### Submit or Update a Review
```http
POST /api/v1/movies/{movieId}/reviews
Authorization: Bearer <token>
Content-Type: application/json

{
  "rating": 4.5,
  "reviewText": "Brilliant storytelling and breathtaking cinematography!"
}
```

#### Get Reviews for a Movie
```http
GET /api/v1/movies/{movieId}/reviews?page=0&size=10
```

#### Get Current User's Review on a Movie
```http
GET /api/v1/movies/{movieId}/reviews/me
Authorization: Bearer <token>
```

#### Get All Reviews Written by a User
```http
# Authenticated user's own reviews
GET /api/v1/movies/reviews/me?page=0&size=10

# Public profile reviews
GET /api/v1/movies/reviews/users/{userId}?page=0&size=10
```

---

### Image CDN & Poster URL Construction
All image URLs returned by `movie-service` are fully-qualified HTTPS CDN links.

| Image Type | Recommended Width | Fallback CDN URL Pattern |
| :--- | :--- | :--- |
| **Poster Card** | `w500` | `https://image.tmdb.org/t/p/w500/{posterPath}` |
| **Hero Backdrop** | `w1280` or `original` | `https://image.tmdb.org/t/p/w1280/{backdropPath}` |
| **Actor Profile** | `w185` | `https://image.tmdb.org/t/p/w185/{profilePath}` |

> **Frontend Implementation Tip:** If `posterUrl` or `backdropUrl` is `null` in the JSON response, render your local UI placeholder asset (e.g. `assets/placeholder-poster.png`).

---

### Standard Error Handling & Status Codes
All error responses adhere to RFC 7807 Problem Details:

```json
{
  "timestamp": "2026-10-25T10:30:00Z",
  "status": 404,
  "error": "Not Found",
  "message": "Movie not found with TMDB ID: 99999999",
  "path": "/api/v1/movies/99999999"
}
```

| HTTP Status | Trigger Condition |
| :--- | :--- |
| `200 OK` | Successful query or idempotent update |
| `201 Created` | New watchlist item or review created |
| `204 No Content` | Item deleted from watchlist |
| `400 Bad Request` | Invalid rating range (`< 1.0` or `> 5.0`), invalid UUID, or bad pagination params |
| `401 Unauthorized` | Missing, expired, or invalid JWT Bearer token |
| `404 Not Found` | Requested movie ID or person ID not found in TMDB or database |
| `500 Server Error` | Unhandled internal exception |

---

## 3. Recommendation Service Integration Guide

The `recommendation-service` interacts with `movie-service` through two channels:
1. **Asynchronous Event Stream (Apache Kafka)**: Ingests real-time rating events to update user-item collaborative filtering matrices and vector embeddings.
2. **Synchronous REST API**: Provides 50 recommended candidate movie IDs when requested by `movie-service`.

---

### Kafka Event Streaming: `movie.rating.v1`

#### Topic Configuration
- **Topic Name**: `movie.rating.v1`
- **Partitions**: `3` (or higher in production clusters)
- **Replication Factor**: `1` (Dev) / `3` (Production)
- **Key Strategy**: `userId.toString()` (Guarantees strict per-user in-order event consumption across partitions)

#### Event Schema (JSON)
```json
{
  "eventId": "c7a8b3e2-62b1-4f11-9a74-2c6c0e819d45",
  "eventType": "MOVIE_RATED",
  "userId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "movieId": 1011985,
  "rating": 4.5,
  "reviewText": "Masterpiece sequel!",
  "timestamp": "2026-10-25T10:45:00.123Z"
}
```

#### Field Specifications
| Field | Type | Description |
| :--- | :--- | :--- |
| `eventId` | UUID string | Unique identifier for event deduplication. |
| `eventType` | String | Fixed: `"MOVIE_RATED"`. |
| `userId` | UUID string | The user who rated the movie. |
| `movieId` | Long | The TMDB Movie ID. |
| `rating` | Double | Rating score (`1.0` to `5.0`). |
| `reviewText` | String | Optional user review text (nullable). |
| `timestamp` | ISO-8601 String | UTC timestamp when rating occurred. |

#### Reliability Guarantee
Events are recorded within the same ACID database transaction as the review using the **Transactional Outbox Pattern** (`outbox_events` table). An asynchronous publisher polls every 2 seconds and publishes to Kafka with `acks=all`, eliminating dual-write failure vulnerabilities.

---

### REST Inbound Contract: Model Predictions

When an authenticated user requests recommendations:
```http
GET /api/v1/recommendations
Authorization: Bearer <token>
```
`movie-service` calls `recommendation-service` downstream:
```http
GET /api/v1/recommendations/{userId}?limit=50
Timeout: 1500ms
```

#### Expected Response Format from `recommendation-service`
```json
{
  "userId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "movieIds": [
    1011985,
    579974,
    350312,
    157336,
    550
  ]
}
```

---

### 50-Movie Waterfall Fallback Guarantee

`movie-service` implements a **fail-safe 3-phase waterfall engine** that guarantees the user **always receives exactly 50 movies**, regardless of cold starts, model latency, or partial candidate pools:

```mermaid
flowchart TD
    Req["User Requests Recommendations"] --> Step1["Call recommendation-service (:8083)<br/>(1500ms Timeout)"]
    Step1 -->|Success & Valid IDs| MLResults["Collect ML Movie IDs"]
    Step1 -->|Timeout / 500 / Network Error| EmptyML["Empty Candidate List"]
    
    MLResults --> CheckCount{"Count >= 50?"}
    EmptyML --> Backfill["Trigger Waterfall Backfill"]
    CheckCount -->|Yes| Sized["Take Top 50"]
    CheckCount -->|No (< 50)| Deficit["Calculate Deficit = (50 - count)"]
    
    Deficit --> Backfill
    Backfill --> Prefs["Load User Preferences (Industries & Genres)"]
    Prefs --> PoolQuery["Query High-Rated Movies Matching Preferences<br/>(vote_average.desc, vote_count >= 50)"]
    PoolQuery --> Blend["Deduplicate & Blend Deficit Items"]
    Blend --> Hydrate["Hydrate MovieCardDTO Objects<br/>(Cache + TMDB)"]
    Sized --> Hydrate
    Hydrate --> Out["Return Exactly 50 Personalized Movies"]
```

---

## 4. User Service & API Gateway Contract

### JWT Security Configuration
- **Algorithm**: `HMAC-SHA256` (`HS256`)
- **Shared Secret Key**: Configurable via `JWT_SECRET` environment variable.
- **Expected Claims in Token**:
  - `sub` or `userId`: UUID string of the authenticated user.
  - `roles`: Array or comma-delimited string (e.g. `["ROLE_USER"]`).
  - `exp`: Standard expiration epoch timestamp.

### Anti-Spoofing Gateway Interceptor
When traffic passes through `api-gateway`:
- The gateway validates JWT signatures.
- Appends `X-User-Id: <uuid>`.
- Appends `X-Gateway-Secret: <secret>`.
- `movie-service` verifies `X-Gateway-Secret` matching `GATEWAY_SHARED_SECRET`, preventing direct unauthenticated spoofing from inside internal networks.

---

## 5. Caching, Data Stores & Operational Engine

### Redis Key Conventions & Eviction
Redis is configured with `--maxmemory 256mb --maxmemory-policy allkeys-lru`.

| Key Pattern | Data Structure | TTL | Purpose |
| :--- | :--- | :--- | :--- |
| `feed:default:{section}:p:{page}` | JSON String | 2 to 6 Hours | Global default feed pages for guests. |
| `feed:pool:{section}:ind:{industry}` | JSON Array | 8 Hours | Atomic candidate pool for a single industry. |
| `feed:pool:{section}:genre:{genre}` | JSON Array | 8 Hours | Atomic candidate pool for a single genre. |
| `movie:detail:{movieId}` | Hash / JSON | 24 Hours | Complete hydrated movie metadata. |
| `lock:movie:{movieId}` | String (`SETNX`) | 5 Seconds | Distributed leader mutex for stampede defense. |
| `search:multi:{queryHash}:p:{page}` | JSON String | 24 Hours | Multi-search results cache. |

---

### Singleflight Leader Mutex (Stampede Guard)
When multiple concurrent requests experience a cache miss for the same cold movie ID:
1. Thread 1 acquires `lock:movie:{id}` via Redis `SETNX` (5-second TTL).
2. Thread 1 fetches metadata from TMDB, writes to Redis and PostgreSQL.
3. Threads 2 through N detect the lock, wait up to 300ms, and cleanly read the newly populated cache.
4. **Result:** Exactly 1 outbound call to TMDB; zero database or upstream saturation.

---

### Proactive Background Pre-Warming
- On application boot and after feed refreshes, `ProactivePrewarmService` scans the **top 10 trending movies**.
- Pre-fetches their full details in an async background worker with a **250ms throttle**.
- By the time users click on a trending movie card, its details are already warm in Redis (0ms cache hit).

---

### Scheduled Refresh Cron Matrix

| Schedule | Job Method | Sections Refreshed |
| :--- | :--- | :--- |
| **Startup (`@EventListener`)** | `onStartup()` | Warms all 5 default feeds + 5 atomic industry pools + 11 genre pools. |
| **Hourly (`0 0 * * * *`)** | `refreshTrendingFeeds()` | Refreshes `TRENDING_TODAY` and `TRENDING_WEEK`. |
| **Every 6 Hours (`0 0 */6 * * *`)** | `refreshCatalogFeeds()` | Refreshes `POPULAR`, `TOP_RATED`, and `NOW_PLAYING`. |
| **Every 2 Seconds** | `publishOutboxEvents()` | Scans `outbox_events` and flushes rating events to Kafka. |

---

## 6. Local Quickstart (Docker Compose)

Spin up the entire stack (PostgreSQL, Redis, Kafka, Topic Initializer, and Movie Service):

```bash
docker compose up -d
```

Verify services are running:
```bash
docker compose ps
```

Tail service logs:
```bash
docker compose logs -f movie-service
```
