# 🎬 Movie Service — System Context & Integration Architecture

This document serves as the **definitive technical specification and integration guide** for:
- 💻 **Frontend Engineers** (Web / Next.js / React, Mobile / React Native / Flutter)
- 🧠 **Recommendation & Data Science Engineers**
- 🔐 **User & Auth Service Engineers** (`user-service` and API Gateway)
- 🛠️ **DevOps & Platform Engineers**

---

## 📑 Table of Contents
1. [System Architecture & Microservice Boundaries](#1-system-architecture--microservice-boundaries)
2. [Frontend Integration Guide](#2-frontend-integration-guide)
   - [Authentication & Request Headers](#authentication--request-headers)
   - [Home Feed Sections & Targeted Feeds](#home-feed-sections--targeted-feeds)
   - [Top 20 Popular Movies API](#top-20-popular-movies-api)
   - [Movie, Actor & Crew Details](#movie-actor--crew-details)
   - [Batch Movie Hydration](#batch-movie-hydration)
   - [Unified Multi-Search](#unified-multi-search)
   - [Social Privacy Controls (Watchlist & Reviews)](#social-privacy-controls-watchlist--reviews)
   - [Watchlist State & Actions](#watchlist-state--actions)
   - [Reviews, 5-Star Ratings & Multi-Mode Sorting](#reviews-5-star-ratings--multi-mode-sorting)
   - [Dynamic TMDB Genre Resolution](#dynamic-tmdb-genre-resolution)
   - [Image CDN & Poster URL Construction](#image-cdn--poster-url-construction)
   - [Standard Error Handling & RFC 7807 Responses](#standard-error-handling--rfc-7807-responses)
3. [Personalized Recommendations Engine](#3-personalized-recommendations-engine)
   - [20-Movie Preference-Based Discovery Algorithm](#20-movie-preference-based-discovery-algorithm)
   - [Kafka Event Streaming: `movie.rating.v1`](#kafka-event-streaming-movieratingv1)
4. [User Service & API Gateway Contract](#4-user-service--api-gateway-contract)
5. [Caching, Data Stores & Operational Engine](#5-caching-data-stores--operational-engine)
   - [Redis Key Conventions & Eviction](#redis-key-conventions--eviction)
   - [In-Memory Genre Cache & Fallbacks](#in-memory-genre-cache--fallbacks)
   - [Singleflight Leader Mutex (Stampede Guard)](#singleflight-leader-mutex-stampede-guard)
   - [Proactive Background Pre-Warming](#proactive-background-pre-warming)
   - [Scheduled Refresh Cron Matrix](#scheduled-refresh-cron-matrix)

---

## 1. System Architecture & Microservice Boundaries

```mermaid
flowchart TD
    Client["Clients (Web / iOS / Android)"]
    Gateway["API Gateway (:8080)"]
    UserService["User Service (:8081)<br/>• Auth / JWT / Profiles<br/>• Social Graph & Privacy State"]
    MovieService["Movie Service (:8082)<br/>• Catalog / Feeds / Details<br/>• Batch Hydration / Search<br/>• Watchlist / Reviews / Preferences<br/>• Privacy Access Interceptor<br/>• 3-Tier Read-Through Cache<br/>• Dynamic TMDB Genre Cache"]
    Postgres[("PostgreSQL 17<br/>• Permanent Catalog<br/>• Watchlists & Reviews<br/>• Transactional Outbox")]
    Redis[("Redis 7 (LRU)<br/>• Section Feeds & Pools<br/>• Detail Caches<br/>• Multi-Search Caches")]
    GraphRedis[("Graph Redis (:6380)<br/>• Follower Relationships<br/>• Privacy Caches")]
    Kafka{"Apache Kafka<br/>• topic: movie.rating.v1"}
    TMDB["TMDB API v3 / v4<br/>• Upstream Global Catalog<br/>• Dynamic Genre Discovery"]

    Client -->|HTTP / JSON| Gateway
    Gateway -->|Validate Token| UserService
    Gateway -->|Proxy Requests| MovieService
    
    MovieService -->|Social Privacy Verification| UserAccessService
    UserAccessService -.->|Query Following Graph| GraphRedis
    UserAccessService -.->|Fallback Privacy REST| UserService

    MovieService -->|Query / Mutex| Redis
    MovieService -->|ACID Persistence| Postgres
    MovieService -->|Cold Miss Fallback| TMDB
    MovieService -->|Publish Ratings via Outbox| Kafka
```

### Responsibility Matrix
| Domain | `movie-service` Responsibility | Other Service Responsibility |
| :--- | :--- | :--- |
| **User Identity** | Validates JWT signatures and parses `userId`. | `user-service` manages passwords, sessions, login/signup. |
| **Privacy Access** | Enforces public vs. private follower-only access on reviews and watchlists. | `user-service` and social graph store follower relationships. |
| **Movie Metadata** | Caches, indexes, enriches, and serves movie, actor, crew details. | TMDB provides raw external global movie data. |
| **Feeds** | Serves 5 dynamic sections, cached candidate pools, and diversity shuffles. | Client renders carousels and paginated grids. |
| **User Interactions**| Persists watchlists, reviews, and platform ratings. Synchronizes Redis cache atomically. | Publishes to Kafka for downstream analytics. |
| **Recommendations** | Fetches top preferred movies by vote count, filters rated items, and ranks by vote average. | Downstream ML services consume Kafka rating events. |

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

### Home Feed Sections & Targeted Feeds
The home feed features **5 dedicated sections** available individually (with pagination) or aggregated in a single request.

#### Supported Industries (`industry` param)
- `ALL` (Global across all languages)
- `HOLLYWOOD` (English - `en`)
- `TOLLYWOOD` (Telugu - `te`)
- `BOLLYWOOD` (Hindi - `hi`)
- `MOLLYWOOD` (Malayalam - `ml`)
- `KOLLYWOOD` (Tamil - `ta`)

#### Supported Core Genres (`genre` param)
Supports all 19 standard TMDB genres: `ACTION`, `ADVENTURE`, `ANIMATION`, `COMEDY`, `CRIME`, `DOCUMENTARY`, `DRAMA`, `FAMILY`, `FANTASY`, `HISTORY`, `HORROR`, `MUSIC`, `MYSTERY`, `ROMANCE`, `SCIENCE_FICTION`, `TV_MOVIE`, `THRILLER`, `WAR`, `WESTERN`.

#### Feed Endpoints
```http
# 1. Trending Today (Released in last 4 weeks up to today + next 7 days, vote_count.desc)
GET /api/v1/movies/home/trending-today?industry=TOLLYWOOD&page=1

# 2. Trending This Week (Released in last 3 months up to today + next 7 days, vote_count.desc)
GET /api/v1/movies/home/trending-week?industry=TOLLYWOOD&page=1

# 3. All-Time Popular (No release date filter, sorted strictly by vote_count.desc)
GET /api/v1/movies/home/popular?industry=TOLLYWOOD&genre=ACTION&page=1

# 4. Top Rated Classics (vote_count >= last result of page 1 / 2, vote_avg >= max - 2.0)
GET /api/v1/movies/home/top-rated?industry=HOLLYWOOD&page=1

# 5. Now Playing (Theatrical release window: last 3 weeks up to today, vote_count.desc)
GET /api/v1/movies/home/now-playing?industry=BOLLYWOOD&page=1

# 6. Aggregated Feed (All 5 sections on Page 1 for initial app launch)
GET /api/v1/movies/home
```

---

### Top 20 Popular Movies API
Designed specifically for movie detail page "More Like This" carousels and genre-specific popular widgets.

```http
GET /api/v1/movies/popular?industry=TOLLYWOOD&genre=ACTION&excludeMovieId=1011985
# Also available at:
GET /api/v1/movies/home/popular/top?industry=TOLLYWOOD&genre=ACTION&excludeMovieId=1011985
```

#### Behavior & Caching Semantics
1. **Cache Hit**: Fetches instantly from Redis home feed pool (`feed:pool:POPULAR:ind:{industry}:genre:{genre}`).
2. **Cache Miss**: If neither industry nor genre is cached in Redis, automatically hits TMDB discover with `vote_count.desc`, returns the movies immediately, and warms the Redis pool asynchronously in the background.
3. **Exclusion**: Omits `excludeMovieId` (or `excludeId`) from the returned list.
4. **Limits**: Returns up to 20 movies.

---

### Movie, Actor & Crew Details

#### 1. Movie Details (Full Cast, Crew, Directors, Overview)
```http
GET /api/v1/movies/{id}
```
**Response (`MovieDetailDTO`):**
```json
{
  \"id\": 550,
  \"title\": \"Fight Club\",
  \"tagline\": \"Mischief. Mayhem. Soap.\",
  \"overview\": \"An insomniac office worker and a devil-may-care soap maker form an underground fight club...\",
  \"posterUrl\": \"https://image.tmdb.org/t/p/w500/pB8BM7pdSp6B6Ih7QZ4DrQ3PmJK.jpg\",\n  \"backdropUrl\": \"https://image.tmdb.org/t/p/w1280/hZkgoQYus5vegHoetLkCJzb17zJ.jpg\",
  \"releaseDate\": \"1999-10-15\",
  \"releaseYear\": 1999,
  \"runtime\": 139,
  \"originalLanguage\": \"en\",
  \"industry\": \"HOLLYWOOD\",
  \"status\": \"Released\",
  \"tmdbVoteAverage\": 8.43,
  \"platformRating\": 4.7,
  \"platformRatingCount\": 1820,
  \"genres\": [\"Drama\", \"Thriller\"],
  \"directors\": [\"David Fincher\"],
  \"writers\": [\"Chuck Palahniuk\", \"Jim Uhls\"],
  \"cast\": [
    {
      \"id\": 287,
      \"name\": \"Brad Pitt\",
      \"character\": \"Tyler Durden\",
      \"profileUrl\": \"https://image.tmdb.org/t/p/w185/cckcYc2v0yh1tc9QjRelptsqpHW.jpg\",
      \"order\": 1
    }
  ],
  \"crew\": [
    {
      \"id\": 7467,
      \"name\": \"David Fincher\",
      \"department\": \"Directing\",\n      \"job\": \"Director\",
      \"profileUrl\": \"https://image.tmdb.org/t/p/w185/director.jpg\"
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

### Batch Movie Hydration
Enables hydrating multiple movie IDs in a single HTTP call (ideal for notification feeds, custom user playlists, and search aggregation).

```http
POST /api/v1/movies/batch
Content-Type: application/json

{
  \"movieIds\": [550, 1011985, 157336]
}
```

#### Multi-Tier Resolution Strategy
```mermaid
flowchart LR
    Req["POST /movies/batch"] --> Step1["1. Check Redis Cache"]
    Step1 -->|Misses| Step2["2. Check PostgreSQL DB"]
    Step2 -->|Misses| Step3["3. Hit TMDB API & Persist"]
    Step1 & Step2 & Step3 --> Merge["Preserve Input Order & Return List"]
```
1. **Tier 1 (Redis)**: Batch reads `movie:detail:{id}` from Redis.
2. **Tier 2 (PostgreSQL)**: Unresolved IDs are queried in bulk from PostgreSQL (`MovieRepository.findAllById`).
3. **Tier 3 (TMDB API)**: Unresolved IDs are fetched individually from TMDB, persisted to PostgreSQL, and cached in Redis.
4. **Ordering**: The returned `List<MovieDetailDTO>` preserves the exact sequence of requested IDs.

---

### Unified Multi-Search
Fast multi-entity search querying Movies, Actors, and Crew simultaneously with 1-day Redis caching.
```http
GET /api/v1/movies/search?query=christopher+nolan&page=1
```

Optional `type` filter:
- `ALL` (default): Movies, Actors, and Crew.
- `MOVIE`: Filters exclusively to movie titles.
- `PERSON`: Filters exclusively to cast and crew members.

---

### Social Privacy Controls (Watchlist & Reviews)

To protect user privacy, access to a user's watchlist and reviews is governed by profile privacy settings:

| Target Profile Privacy | Requester Relationship | Allowed Actions | Response on Failure |
| :--- | :--- | :--- | :--- |
| **PUBLIC** | Anyone (Authenticated or Guest) | View Watchlist & Reviews | `200 OK` |
| **PRIVATE** | Self (Owner) | View Watchlist & Reviews | `200 OK` |
| **PRIVATE** | Confirmed Follower | View Watchlist & Reviews | `200 OK` |
| **PRIVATE** | Non-Follower | View Watchlist & Reviews | `403 Forbidden` |

#### Movie Reviews Feed Filtering
When querying public reviews for a movie (`GET /api/v1/movies/{movieId}/reviews`):
- Reviews authored by **private users whom the requester is not following** are automatically filtered out.
- The requester's own review is always retained.
- Public users' reviews are always visible.

---

### Watchlist State & Actions

#### Check Status (Bookmark Icon on Movie Detail Page)
```http
GET /api/v1/movies/{movieId}/watchlist/status
Authorization: Bearer <token>
```
**Response:**
```json
{
  \"inWatchlist\": true
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

#### Get User's Paginated Watchlist
```http
# Authenticated user's own watchlist
GET /api/v1/movies/watchlist/me?page=0&size=20
Authorization: Bearer <token>

# Other user's watchlist (Privacy Enforced)
GET /api/v1/movies/watchlist/users/{userId}?page=0&size=20
Authorization: Bearer <token>
```

---

### Reviews, 5-Star Ratings & Multi-Mode Sorting

#### Rules & Constraints
- Ratings are numeric from `1.0` to `5.0` (typically half-star increments: `1.0, 1.5, ..., 5.0`).
- Maximum review text length: 2,000 characters.
- One review per user per movie (`POST` performs an idempotent upsert).
- **Atomic Cache Synchronization**: Updating a review recalculates the platform rating average and immediately updates both PostgreSQL and the cached movie detail in Redis (`movie:detail:{movieId}`).
- **Transactional Outbox**: Emits a `movie.rating.v1` event to Kafka inside the same transaction.

#### Submit or Update a Review
```http
POST /api/v1/movies/{movieId}/reviews
Authorization: Bearer <token>
Content-Type: application/json

{
  \"rating\": 4.5,
  \"reviewText\": \"Brilliant storytelling and breathtaking cinematography!\"
}
```

#### Read Movie Reviews (With Sort & Pagination)
```http
GET /api/v1/movies/{movieId}/reviews?page=0&size=10&sort=top
```
Supported `sort` parameters:
- `latest` (default): Newest reviews first (`createdAt DESC`).
- `top`: Highest rated first (`rating DESC, createdAt DESC`).
- `low`: Lowest rated first (`rating ASC, createdAt DESC`).

#### Read Reviews Written by Authenticated User
```http
GET /api/v1/movies/reviews/me?page=0&size=10&sort=latest
Authorization: Bearer <token>
```

#### Read Reviews Written by Specific User (Privacy Enforced)
```http
GET /api/v1/movies/reviews/users/{userId}?page=0&size=10&sort=latest
Authorization: Bearer <token>
```

---

### Dynamic TMDB Genre Resolution

The service dynamically discovers and maps genres:
- **Fallback Registry**: Preloaded with all 19 standard TMDB genres.
- **Dynamic Fetch**: If an unknown genre ID or name is encountered, `TmdbService` invokes TMDB `GET /genre/movie/list` to register the new genre in memory.
- **Query Parameter Resolution**: Translates textual genre names (e.g. `genre=ACTION` or `genre=Sci-Fi`) or comma-separated lists (`genre=ACTION,DRAMA`) directly into numeric TMDB genre IDs for upstream discover queries.

---

### Image CDN & Poster URL Construction
All image URLs returned by `movie-service` are fully-qualified HTTPS CDN links.

| Image Type | Recommended Width | Fallback CDN URL Pattern |
| :--- | :--- | :--- |
| **Poster Card** | `w500` | `https://image.tmdb.org/t/p/w500/{posterPath}` |
| **Hero Backdrop** | `w1280` or `original` | `https://image.tmdb.org/t/p/w1280/{backdropPath}` |
| **Actor Profile** | `w185` | `https://image.tmdb.org/t/p/w185/{profilePath}` |

---

### Standard Error Handling & RFC 7807 Responses
All error responses adhere to standard RFC 7807 Problem Details:

```json
{
  \"timestamp\": \"2026-11-04T09:15:00Z\",
  \"status\": 403,
  \"error\": \"Forbidden\",
  \"message\": \"User profile is private. You must follow this user to view their reviews.\",
  \"path\": \"/api/v1/movies/reviews/users/3fa85f64-5717-4562-b3fc-2c963f66afa6\"
}
```

| HTTP Status | Trigger Condition |
| :--- | :--- |
| `200 OK` | Successful query or idempotent update |
| `201 Created` | New watchlist item or review created |
| `204 No Content` | Item deleted from watchlist |
| `400 Bad Request` | Invalid rating range (`< 1.0` or `> 5.0`), invalid UUID, or bad pagination params |
| `401 Unauthorized` | Missing, expired, or invalid JWT Bearer token |
| `403 Forbidden` | Access denied due to user profile privacy settings (private profile, not following) |
| `404 Not Found` | Requested movie ID or person ID not found in TMDB or database |
| `500 Server Error` | Unhandled internal exception |

---

## 3. Personalized Recommendations Engine

### 20-Movie Preference-Based Discovery Algorithm

When an authenticated user requests personalized recommendations:
```http
GET /api/v1/movies/recommendations
Authorization: Bearer <token>
```

The service applies a 4-step pipeline:

```mermaid
flowchart TD
    Req["User Calls /recommendations"] --> Prefs["1. Load User Preferences<br/>(Preferred Industries & Genres)"]
    Prefs --> TMDBDiscover["2. Fetch Top 50 from TMDB Discover<br/>(sort_by=vote_count.desc)"]
    TMDBDiscover --> FilterRated["3. Filter Out Movies Already Rated<br/>by User in Review Table"]
    FilterRated --> ReRank["4. Re-rank Remaining Candidates<br/>by vote_average.desc"]
    ReRank --> Limit20["5. Return Top 20 Recommendations"]
```

1. **Preference Resolution**: Loads the user's preferred industries and genres from Redis cache or PostgreSQL.
2. **Upstream Discovery Pool**: Hits TMDB discover API for the preferred industries and genres sorted by `vote_count.desc`, retrieving the top 50 high-confidence candidate movies.
3. **Activity Deduplication**: Fetches all movie IDs rated by the user from PostgreSQL and removes them from the candidate pool.
4. **Quality Re-ranking**: Sorts the unrated candidate movies by `vote_average` descending.
5. **Exact Limit**: Returns the top 20 personalized movie cards.

---

### Kafka Event Streaming: `movie.rating.v1`

Rating events are produced whenever a user rates or reviews a movie. Downstream analytics and machine learning services consume these events to retrain models.

#### Topic Configuration
- **Topic Name**: `movie.rating.v1`
- **Partitions**: `3` (KRaft cluster)
- **Key Strategy**: `userId.toString()` (Guarantees strict per-user in-order event consumption across partitions)

#### Event Schema (JSON)
```json
{
  \"eventId\": \"c7a8b3e2-62b1-4f11-9a74-2c6c0e819d45\",
  \"eventType\": \"MOVIE_RATED\",
  \"userId\": \"3fa85f64-5717-4562-b3fc-2c963f66afa6\",
  \"movieId\": 1011985,
  \"rating\": 4.5,
  \"reviewText\": \"Masterpiece sequel!\",
  \"timestamp\": \"2026-11-04T09:15:00.123Z\"
}
```

#### Reliability Guarantee
Events are recorded in the `outbox_events` table inside the same transaction as the review. A scheduled poller runs every 2 seconds, publishing pending events to Kafka with `acks=all`.

---

## 4. User Service & API Gateway Contract

### JWT Security Configuration
- **Algorithm**: `HMAC-SHA256` (`HS256`)
- **Shared Secret Key**: Configurable via `JWT_SECRET` environment variable.
- **Claims**:
  - `sub` or `userId`: UUID string of the authenticated user.
  - `roles`: User roles (e.g. `[\"ROLE_USER\"]`).
  - `exp`: Token expiration timestamp.

### Anti-Spoofing Gateway Interceptor
- When requests pass through the API Gateway:
  - Appends `X-User-Id: <uuid>`.
  - Appends `X-Gateway-Secret: <secret>`.
- `movie-service` verifies `X-Gateway-Secret` matching `GATEWAY_SHARED_SECRET` to prevent bypass attacks.

---

## 5. Caching, Data Stores & Operational Engine

### Redis Key Conventions & Eviction
Redis is configured with `--maxmemory 256mb --maxmemory-policy allkeys-lru`.

| Key Pattern | Data Structure | TTL | Purpose |
| :--- | :--- | :--- | :--- |
| `feed:default:{section}:p:{page}` | JSON String | 2 to 6 Hours | Global default feed pages for guests. |
| `feed:pool:{section}:ind:{ind}:genre:{genre}` | JSON Array | 8 Hours | 250 Combined candidate pools. |
| `movie:detail:{movieId}` | Hash / JSON | 24 Hours | Complete hydrated movie metadata. |
| `lock:movie:{movieId}` | String (`SETNX`) | 5 Seconds | Distributed leader mutex for stampede defense. |
| `search:multi:{queryHash}:p:{page}` | JSON String | 24 Hours | Multi-search results cache. |
| `user:pref:{userId}` | JSON String | 24 Hours | User preferences cache. |

---

### In-Memory Genre Cache & Fallbacks
- Bidirectional concurrent maps: `genreIdToNameCache` and `genreNameToIdCache`.
- Initialized on boot via `MockTmdbDataProvider.getFallbackGenres()`.
- Thread-safe lazy loading hitting TMDB `/genre/movie/list` whenever an unmapped genre is requested.

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
- Trending movie cards are warm in Redis before user click interactions.

---

### Scheduled Refresh Cron Matrix

| Schedule | Job Method | Sections Refreshed |
| :--- | :--- | :--- |
| **Startup (`@EventListener`)** | `onStartup()` | Warms default feeds + 250 combined industry/genre pools. |
| **Hourly (`0 0 * * * *`)** | `refreshTrendingFeeds()` | Refreshes `TRENDING_TODAY` and `TRENDING_WEEK`. |
| **Every 6 Hours (`0 0 */6 * * *`)** | `refreshCatalogFeeds()` | Refreshes `POPULAR`, `TOP_RATED`, and `NOW_PLAYING`. |
| **Every 2 Seconds** | `publishOutboxEvents()` | Scans `outbox_events` and flushes rating events to Kafka. |
