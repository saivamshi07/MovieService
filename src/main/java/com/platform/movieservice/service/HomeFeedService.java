package com.platform.movieservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.movieservice.context.UserContext;
import com.platform.movieservice.dto.response.MovieCardDTO;
import com.platform.movieservice.dto.response.SectionResponseDTO;
import com.platform.movieservice.dto.response.UserPreferenceResponse;
import com.platform.movieservice.dto.tmdb.TmdbMovieResult;
import com.platform.movieservice.dto.tmdb.TmdbPageResponse;
import com.platform.movieservice.entity.enums.CinemaIndustry;
import com.platform.movieservice.entity.enums.MovieGenre;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class HomeFeedService {

    private final TmdbService tmdbService;
    private final RedisTemplate<String, Object> redisTemplate;
    private final UserPreferenceService userPreferenceService;
    private final ProactivePrewarmService proactivePrewarmService;
    private final ObjectMapper objectMapper;

    @Value("${prewarm.throttle-delay-ms:50}")
    private int throttleDelayMs = 50;

    @Value("${feed.warmup.enabled:true}")
    private boolean warmupEnabled = true;

    // Section constants
    public static final String SECTION_TRENDING_TODAY = "TRENDING_TODAY";
    public static final String SECTION_TRENDING_WEEK = "TRENDING_WEEK";
    public static final String SECTION_POPULAR = "POPULAR";
    public static final String SECTION_TOP_RATED = "TOP_RATED";
    public static final String SECTION_NOW_PLAYING = "NOW_PLAYING";

    private static final int DEFAULT_PAGE_SIZE = 20;

    // Cache key templates (5 sections x 5 industries x 10/11 genres = 250 combinations)
    public static final String DEFAULT_POOL_KEY = "feed:default:%s:p:%d";
    public static final String COMBINED_POOL_KEY = "feed:pool:%s:ind:%s:genre:%s";

    private static final Comparator<TmdbMovieResult> VOTE_COUNT_THEN_POPULARITY_COMPARATOR = Comparator
            .comparing((TmdbMovieResult r) -> r.getVoteCount() != null ? r.getVoteCount() : 0L, Comparator.reverseOrder())
            .thenComparing((TmdbMovieResult r) -> r.getPopularity() != null ? r.getPopularity() : 0.0, Comparator.reverseOrder());

    private static final Comparator<TmdbMovieResult> VOTE_AVERAGE_THEN_VOTE_COUNT_COMPARATOR = Comparator
            .comparing((TmdbMovieResult r) -> r.getVoteAverage() != null ? r.getVoteAverage() : 0.0, Comparator.reverseOrder())
            .thenComparing((TmdbMovieResult r) -> r.getVoteCount() != null ? r.getVoteCount() : 0L, Comparator.reverseOrder())
            .thenComparing((TmdbMovieResult r) -> r.getPopularity() != null ? r.getPopularity() : 0.0, Comparator.reverseOrder());

    public SectionResponseDTO getSectionFeed(String section, int page) {
        return getSectionFeed(section, page, null, null, null);
    }

    public SectionResponseDTO getSectionFeed(String section, int page, String industryParam, String genreParam) {
        return getSectionFeed(section, page, industryParam, genreParam, null);
    }

    /**
     * Resolves the feed section for caller.
     * 1. If explicit industry or genre query param is passed, serves targeted filtered feed (cached in Redis).
     * 2. If user is authenticated, composites user-preferred industry & genre pools with diversity shuffle.
     * 3. If guest / unauthenticated, returns the default global section.
     * Guarantees that movies within each section contain no duplicates.
     */
    public SectionResponseDTO getSectionFeed(String section, int page, String industryParam, String genreParam, Long excludeMovieId) {
        int safePage = Math.max(page, 1);

        SectionResponseDTO response;
        // 1. Explicit query parameter takes highest priority
        if (StringUtils.hasText(industryParam) || StringUtils.hasText(genreParam)) {
            response = getFilteredFeed(section, safePage, industryParam, genreParam);
        } else {
            UUID userId = UserContext.getUserId();
            if (userId != null) {
                UserPreferenceResponse preferences = userPreferenceService.getPreferences(userId);
                if (preferences != null && (!preferences.getIndustries().isEmpty() || !preferences.getGenres().isEmpty())) {
                    response = buildCompositeFeed(section, safePage, preferences);
                } else {
                    response = getDefaultFeed(section, safePage);
                }
            } else {
                // Default global feed (Guest or User with no preferences)
                response = getDefaultFeed(section, safePage);
            }
        }

        // Guarantee strictly distinct movies within each section and exclude movie ID if specified
        if (response != null && response.getMovies() != null && !response.getMovies().isEmpty()) {
            List<MovieCardDTO> distinctMovies = deduplicateMovies(response.getMovies());
            if (excludeMovieId != null) {
                distinctMovies = distinctMovies.stream()
                        .filter(m -> m != null && !excludeMovieId.equals(m.getId()))
                        .collect(Collectors.toList());
            }
            response.setMovies(distinctMovies);
            response.setTotalResults(distinctMovies.size());
        }

        return response;
    }

    /**
     * Returns top 20 popular movies for the specified industry and genre from the Redis home feed pool.
     * On cache miss, fetches on-demand from TMDB discover (SECTION_POPULAR with vote_count.desc)
     * and asynchronously warms the Redis pool in the background.
     * Optionally excludes excludeMovieId from the returned list.
     */
    public List<MovieCardDTO> getTopPopularMovies(String industryParam, String genreParam, Long excludeMovieId) {
        CinemaIndustry industry = StringUtils.hasText(industryParam) ? CinemaIndustry.fromString(industryParam.trim()) : CinemaIndustry.ALL;
        MovieGenre genre = StringUtils.hasText(genreParam) ? MovieGenre.fromString(genreParam.trim()) : null;

        String indName = (industry != null && industry != CinemaIndustry.ALL) ? industry.name() : null;
        String langCode = (industry != null && industry != CinemaIndustry.ALL) ? industry.getOriginalLanguageCode() : null;

        Integer resolvedGenreId = StringUtils.hasText(genreParam) ? tmdbService.resolveGenreId(genreParam.trim()) : null;
        String genreId = resolvedGenreId != null ? String.valueOf(resolvedGenreId) : (genre != null ? String.valueOf(genre.getTmdbId()) : (StringUtils.hasText(genreParam) ? genreParam.trim() : null));
        String genreName = genre != null ? genre.name() : (resolvedGenreId != null ? tmdbService.resolveGenreName(resolvedGenreId).toUpperCase().replace(" ", "_") : (StringUtils.hasText(genreParam) ? genreParam.trim().toUpperCase() : null));

        String keyInd = indName != null ? indName : "ALL";
        String keyGenre = genreName != null ? genreName : "ALL";
        String poolKey = String.format(COMBINED_POOL_KEY, SECTION_POPULAR, keyInd, keyGenre);

        List<MovieCardDTO> poolCards = null;

        // 1. Try reading from combined pool key in Redis: feed:pool:POPULAR:ind:{industry}:genre:{genre}
        try {
            Object cached = redisTemplate.opsForValue().get(poolKey);
            if (cached instanceof List<?> rawList) {
                poolCards = castOrConvertList(rawList);
            }
        } catch (Exception e) {
            log.warn("Cache read error for pool {}: {}", poolKey, e.getMessage());
        }

        // 2. Cache miss -> fetch on-demand from TMDB discover (vote_count.desc) and cache in background asynchronously
        if (poolCards == null || poolCards.isEmpty()) {
            try {
                TmdbPageResponse<TmdbMovieResult> pageResp = fetchFromTmdbForSection(
                        SECTION_POPULAR,
                        langCode,
                        genreId,
                        1
                );
                poolCards = deduplicateMovies(mapToCards(pageResp.getResults()));
                if (!poolCards.isEmpty()) {
                    final List<MovieCardDTO> toCache = poolCards;
                    CompletableFuture.runAsync(() -> {
                        try {
                            redisTemplate.opsForValue().set(poolKey, toCache, Duration.ofHours(8));
                        } catch (Exception e) {
                            log.warn("Async cache write failed for {}: {}", poolKey, e.getMessage());
                        }
                    });
                }
            } catch (Exception e) {
                log.warn("Failed on-demand fetch for {}: {}", poolKey, e.getMessage());
            }
        }

        if (poolCards == null) {
            poolCards = new ArrayList<>();
        }

        // 3. Filter out excluded movie ID if provided
        List<MovieCardDTO> filtered = poolCards.stream()
                .filter(c -> c != null && (excludeMovieId == null || !excludeMovieId.equals(c.getId())))
                .collect(Collectors.toList());

        if (filtered.size() > DEFAULT_PAGE_SIZE) {
            return new ArrayList<>(filtered.subList(0, DEFAULT_PAGE_SIZE));
        }
        return filtered;
    }

    private List<MovieCardDTO> castOrConvertList(List<?> rawList) {
        if (rawList == null || rawList.isEmpty()) {
            return new ArrayList<>();
        }
        List<MovieCardDTO> result = new ArrayList<>(rawList.size());
        for (Object item : rawList) {
            if (item instanceof MovieCardDTO card) {
                result.add(card);
            } else if (item != null) {
                try {
                    result.add(objectMapper.convertValue(item, MovieCardDTO.class));
                } catch (Exception e) {
                    log.warn("Failed to convert cached item to MovieCardDTO: {}", e.getMessage());
                }
            }
        }
        return result;
    }

    private SectionResponseDTO getFilteredFeed(String section, int page, String industryParam, String genreParam) {
        String langCode = null;
        String indName = null;
        if (StringUtils.hasText(industryParam)) {
            CinemaIndustry industry = CinemaIndustry.fromString(industryParam.trim());
            if (industry != CinemaIndustry.ALL) {
                langCode = industry.getOriginalLanguageCode();
                indName = industry.name();
            }
        }

        String genreId = null;
        String genreName = null;
        if (StringUtils.hasText(genreParam)) {
            Integer resolvedId = tmdbService.resolveGenreId(genreParam.trim());
            MovieGenre genre = MovieGenre.fromString(genreParam.trim());
            if (resolvedId != null) {
                genreId = String.valueOf(resolvedId);
                genreName = genre != null ? genre.name() : tmdbService.resolveGenreName(resolvedId).toUpperCase().replace(" ", "_");
            } else if (genre != null) {
                genreId = String.valueOf(genre.getTmdbId());
                genreName = genre.name();
            } else {
                genreId = genreParam.trim();
                genreName = genreParam.trim().toUpperCase();
            }
        }

        // Try Redis combined pool if industry & genre requested on page 1
        if (indName != null && genreName != null && page == 1) {
            String poolKey = String.format(COMBINED_POOL_KEY, section, indName, genreName);
            try {
                Object cached = redisTemplate.opsForValue().get(poolKey);
                if (cached instanceof List<?> rawList) {
                    List<MovieCardDTO> cards = castOrConvertList(rawList);
                    if (!cards.isEmpty()) {
                        List<MovieCardDTO> distinctCards = deduplicateMovies(cards);
                        return SectionResponseDTO.builder()
                                .section(section)
                                .title(formatSectionTitle(section) + " (" + indName + " - " + genreName + ")")
                                .page(1)
                                .totalPages(1)
                                .totalResults(distinctCards.size())
                                .movies(distinctCards)
                                .build();
                    }
                }
            } catch (Exception ignored) {}
        }

        // Fetch from TMDB discover
        TmdbPageResponse<TmdbMovieResult> tmdbPage = fetchFromTmdbForSection(section, langCode, genreId, page);
        List<MovieCardDTO> movieCards = deduplicateMovies(mapToCards(tmdbPage.getResults()));

        String titleSuffix = "";
        if (indName != null) titleSuffix += " - " + indName;
        if (genreName != null) titleSuffix += " (" + genreName + ")";

        return SectionResponseDTO.builder()
                .section(section)
                .title(formatSectionTitle(section))
                .page(tmdbPage.getPage() > 0 ? tmdbPage.getPage() : page)
                .totalPages(tmdbPage.getTotalPages())
                .totalResults(tmdbPage.getTotalResults())
                .movies(movieCards)
                .build();
    }

    private SectionResponseDTO getDefaultFeed(String section, int page) {
        String cacheKey = String.format(DEFAULT_POOL_KEY, section, page);
        try {
            Object cached = redisTemplate.opsForValue().get(cacheKey);
            if (cached instanceof SectionResponseDTO) {
                SectionResponseDTO dto = (SectionResponseDTO) cached;
                dto.setMovies(deduplicateMovies(dto.getMovies()));
                return dto;
            }
        } catch (Exception e) {
            log.warn("Cache read error for {}: {}", cacheKey, e.getMessage());
        }

        // Cache miss -> Fetch directly from TMDB
        TmdbPageResponse<TmdbMovieResult> tmdbPage = fetchFromTmdbForSection(section, null, null, page);
        List<MovieCardDTO> movieCards = deduplicateMovies(mapToCards(tmdbPage.getResults()));

        SectionResponseDTO response = SectionResponseDTO.builder()
                .section(section)
                .title(formatSectionTitle(section))
                .page(tmdbPage.getPage() > 0 ? tmdbPage.getPage() : page)
                .totalPages(tmdbPage.getTotalPages())
                .totalResults(tmdbPage.getTotalResults())
                .movies(movieCards)
                .build();

        // Warm Redis
        try {
            redisTemplate.opsForValue().set(cacheKey, response, Duration.ofHours(2));
        } catch (Exception ignored) {}

        return response;
    }

    /**
     * Composites candidates from atomic (section, industry, genre) pools stored in Redis.
     * Enforces STRICT language filtering so users only see movies from their preferred cinema industries/languages.
     */
    private SectionResponseDTO buildCompositeFeed(String section, int page, UserPreferenceResponse preferences) {
        List<MovieCardDTO> candidatePool = new ArrayList<>();

        Set<String> preferredIndustries = (preferences != null && preferences.getIndustries() != null)
                ? preferences.getIndustries().stream()
                        .filter(StringUtils::hasText)
                        .map(String::trim)
                        .map(String::toUpperCase)
                        .collect(Collectors.toSet())
                : Set.of();

        Set<String> preferredGenres = (preferences != null && preferences.getGenres() != null)
                ? preferences.getGenres().stream()
                        .filter(StringUtils::hasText)
                        .map(String::trim)
                        .map(String::toUpperCase)
                        .collect(Collectors.toSet())
                : Set.of();

        // Target industries: either user's selected preferences or all active industries
        Set<String> targetIndustries = !preferredIndustries.isEmpty()
                ? preferredIndustries
                : Arrays.stream(CinemaIndustry.values())
                        .filter(i -> i != CinemaIndustry.ALL)
                        .map(Enum::name)
                        .collect(Collectors.toSet());

        // Target genres: either user's selected preferences or all supported genres
        Set<String> targetGenres = !preferredGenres.isEmpty()
                ? preferredGenres
                : Arrays.stream(MovieGenre.values())
                        .map(Enum::name)
                        .collect(Collectors.toSet());

        // 1. Pull candidates from Redis combined (section, industry, genre) pools
        for (String indCode : targetIndustries) {
            CinemaIndustry industry = CinemaIndustry.fromString(indCode);
            if (industry == null || industry == CinemaIndustry.ALL) continue;

            for (String genreCode : targetGenres) {
                MovieGenre genre = MovieGenre.fromString(genreCode);
                if (genre == null) continue;

                String poolKey = String.format(COMBINED_POOL_KEY, section, industry.name(), genre.name());
                List<MovieCardDTO> poolCards = null;
                try {
                    Object cached = redisTemplate.opsForValue().get(poolKey);
                    if (cached instanceof List<?> rawList) {
                        poolCards = castOrConvertList(rawList);
                    }
                } catch (Exception e) {
                    log.warn("Cache read error for pool {}: {}", poolKey, e.getMessage());
                }

                // Cold miss on-demand fetch & cache
                if (poolCards == null || poolCards.isEmpty()) {
                    try {
                        TmdbPageResponse<TmdbMovieResult> pageResp = fetchFromTmdbForSection(
                                section,
                                industry.getOriginalLanguageCode(),
                                String.valueOf(genre.getTmdbId()),
                                1
                        );
                        poolCards = deduplicateMovies(mapToCards(pageResp.getResults()));
                        if (!poolCards.isEmpty()) {
                            redisTemplate.opsForValue().set(poolKey, poolCards, Duration.ofHours(8));
                        }
                    } catch (Exception e) {
                        log.warn("Failed on-demand fetch for {}: {}", poolKey, e.getMessage());
                    }
                }

                if (poolCards != null && !poolCards.isEmpty()) {
                    candidatePool.addAll(poolCards);
                }
            }
        }

        // 2. STRICT Language/Industry Filter:
        // When a user selects specific industries (e.g. TOLLYWOOD), NEVER display other language movies (e.g. English)
        if (!preferredIndustries.isEmpty()) {
            Set<String> allowedLangCodes = preferredIndustries.stream()
                    .map(CinemaIndustry::fromString)
                    .filter(Objects::nonNull)
                    .map(CinemaIndustry::getOriginalLanguageCode)
                    .filter(Objects::nonNull)
                    .map(String::toLowerCase)
                    .collect(Collectors.toSet());

            candidatePool = candidatePool.stream()
                    .filter(card -> preferredIndustries.contains(card.getIndustry())
                            || (card.getOriginalLanguage() != null && allowedLangCodes.contains(card.getOriginalLanguage().toLowerCase())))
                    .collect(Collectors.toList());
        }

        // 3. Fallback if candidate pool is empty:
        // Do NOT fall back to global English feed! Fetch directly for the user's primary preferred industry.
        if (candidatePool.isEmpty()) {
            if (!preferredIndustries.isEmpty()) {
                String primaryIndustry = preferredIndustries.iterator().next();
                String primaryGenre = !preferredGenres.isEmpty() ? preferredGenres.iterator().next() : null;
                return getFilteredFeed(section, page, primaryIndustry, primaryGenre);
            }
            return getDefaultFeed(section, page);
        }

        // 4. De-duplicate by Movie ID (preserving high-relevance matches)
        List<MovieCardDTO> blendedList = deduplicateMovies(candidatePool);

        // 5. Ranking & Diversity:
        if (SECTION_TOP_RATED.equals(section)) {
            blendedList = rankTopRatedClassicsByVoteCountBuckets(blendedList);
        } else if (SECTION_TRENDING_TODAY.equals(section) || SECTION_TRENDING_WEEK.equals(section)
                || SECTION_NOW_PLAYING.equals(section) || SECTION_POPULAR.equals(section)) {
            blendedList.sort(Comparator
                    .comparing((MovieCardDTO c) -> c.getTmdbVoteCount() != null ? c.getTmdbVoteCount() : 0L, Comparator.reverseOrder())
                    .thenComparing((MovieCardDTO c) -> c.getPopularity() != null ? c.getPopularity() : 0.0, Comparator.reverseOrder()));
        } else {
            // Diversity Shuffle for Catalog feeds: Seeded with (page + day) so pagination is stable during browsing
            long seed = System.currentTimeMillis() / (1000 * 60 * 30); // Stable for 30 minutes
            Collections.shuffle(blendedList, new Random(seed + page));
        }

        // 6. Pagination slicing
        int fromIndex = (page - 1) * DEFAULT_PAGE_SIZE;
        if (fromIndex >= blendedList.size()) {
            fromIndex = 0;
        }
        int toIndex = Math.min(fromIndex + DEFAULT_PAGE_SIZE, blendedList.size());
        List<MovieCardDTO> pagedMovies = blendedList.subList(fromIndex, toIndex);

        return SectionResponseDTO.builder()
                .section(section)
                .title(formatSectionTitle(section))
                .page(page)
                .totalPages((int) Math.ceil((double) blendedList.size() / DEFAULT_PAGE_SIZE))
                .totalResults(blendedList.size())
                .movies(pagedMovies)
                .build();
    }

    /**
     * Application Startup Event: Loads default feeds and atomic pools into Redis immediately on boot.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        if (!warmupEnabled) {
            log.info("Movie Service started: feed warmup on startup is disabled.");
            return;
        }
        log.info("Movie Service started: warming home feed pools in Redis in background...");
        CompletableFuture.runAsync(this::refreshAllFeeds);
    }

    /**
     * Hourly Scheduled Refresh for Trending Feeds.
     */
    @Scheduled(cron = "0 0 * * * *") // Every hour
    public void refreshTrendingFeeds() {
        log.info("Refreshing Trending Home Feeds in Redis...");
        refreshSection(SECTION_TRENDING_TODAY);
        refreshSection(SECTION_TRENDING_WEEK);
    }

    /**
     * 6-Hour Scheduled Refresh for Popular, Top Rated, and Now Playing Feeds.
     */
    @Scheduled(cron = "0 0 */6 * * *")
    public void refreshCatalogFeeds() {
        log.info("Refreshing Catalog Feeds (Popular, Top Rated, Now Playing) in Redis...");
        refreshSection(SECTION_POPULAR);
        refreshSection(SECTION_TOP_RATED);
        refreshSection(SECTION_NOW_PLAYING);
    }

    public void refreshAllFeeds() {
        refreshSection(SECTION_TRENDING_TODAY);
        refreshSection(SECTION_TRENDING_WEEK);
        refreshSection(SECTION_POPULAR);
        refreshSection(SECTION_TOP_RATED);
        refreshSection(SECTION_NOW_PLAYING);
    }

    private void refreshSection(String section) {
        try {
            // 1. Refresh Default Pool (Page 1)
            TmdbPageResponse<TmdbMovieResult> defaultPage = fetchFromTmdbForSection(section, null, null, 1);
            List<MovieCardDTO> defaultCards = deduplicateMovies(mapToCards(defaultPage.getResults()));
            SectionResponseDTO defaultResponse = SectionResponseDTO.builder()
                    .section(section)
                    .title(formatSectionTitle(section))
                    .page(1)
                    .totalPages(defaultPage.getTotalPages())
                    .totalResults(defaultPage.getTotalResults())
                    .movies(defaultCards)
                    .build();

            redisTemplate.opsForValue().set(String.format(DEFAULT_POOL_KEY, section, 1), defaultResponse, Duration.ofHours(6));

            // Proactively prewarm details for the top trending items!
            if (SECTION_TRENDING_TODAY.equals(section) && !defaultCards.isEmpty()) {
                List<Long> topIds = defaultCards.stream().map(MovieCardDTO::getId).toList();
                proactivePrewarmService.prewarmTopMovies(topIds);
            }

            // 2. Refresh 250 Combined Pools: (5 sections x 5 industries x 10/11 genres)
            for (CinemaIndustry industry : CinemaIndustry.values()) {
                if (industry == CinemaIndustry.ALL) continue;

                for (MovieGenre genre : MovieGenre.values()) {
                    throttle();
                    TmdbPageResponse<TmdbMovieResult> poolPage = fetchFromTmdbForSection(
                            section,
                            industry.getOriginalLanguageCode(),
                            String.valueOf(genre.getTmdbId()),
                            1
                    );
                    List<MovieCardDTO> poolCards = deduplicateMovies(mapToCards(poolPage.getResults()));
                    if (!poolCards.isEmpty()) {
                        redisTemplate.opsForValue().set(
                                String.format(COMBINED_POOL_KEY, section, industry.name(), genre.name()),
                                poolCards,
                                Duration.ofHours(8)
                        );
                    }
                }
            }

            log.debug("Successfully refreshed Redis pools for section: {}", section);
        } catch (Exception e) {
            log.error("Failed to refresh section {}: {}", section, e.getMessage());
        }
    }

    private void throttle() {
        if (throttleDelayMs > 0) {
            try {
                Thread.sleep(throttleDelayMs);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private TmdbPageResponse<TmdbMovieResult> fetchFromTmdbForSection(String section, String origLang, String genreId, int page) {
        LocalDate today = LocalDate.now();

        if (SECTION_TRENDING_TODAY.equals(section)) {
            // Trending Today: last 4 weeks up to today + next 7 days (-4 weeks to +7 days), sort_by vote_count desc, then popularity desc
            String gte = today.minusWeeks(4).toString();
            String lte = today.plusDays(7).toString();
            TmdbPageResponse<TmdbMovieResult> resp = tmdbService.discoverMovies(origLang, genreId, "vote_count.desc", gte, lte, null, page);
            sortResultsByVoteCountThenPopularity(resp);
            return resp;
        }

        if (SECTION_TRENDING_WEEK.equals(section)) {
            // Trending This Week: last 3 months up to today + next 7 days (-3 months to +7 days), sort_by vote_count desc, then popularity desc
            String gte = today.minusMonths(3).toString();
            String lte = today.plusDays(7).toString();
            TmdbPageResponse<TmdbMovieResult> resp = tmdbService.discoverMovies(origLang, genreId, "vote_count.desc", gte, lte, null, page);
            sortResultsByVoteCountThenPopularity(resp);
            return resp;
        }

        if (SECTION_NOW_PLAYING.equals(section)) {
            // Now in theatres: last 3 weeks up to today, sort_by vote_count desc, then popularity desc
            String gte = today.minusWeeks(3).toString();
            String lte = today.toString();
            TmdbPageResponse<TmdbMovieResult> resp = tmdbService.discoverMovies(origLang, genreId, "vote_count.desc", gte, lte, null, page);
            sortResultsByVoteCountThenPopularity(resp);
            return resp;
        }

        if (SECTION_POPULAR.equals(section)) {
            // All-Time Popular: Hits TMDB with sort_by vote_count.desc (no date filter), returns sorted by vote count desc
            TmdbPageResponse<TmdbMovieResult> resp = tmdbService.discoverMovies(origLang, genreId, "vote_count.desc", null, null, null, page);
            sortResultsByVoteCountThenPopularity(resp);
            return resp;
        }

        if (SECTION_TOP_RATED.equals(section)) {
            // Top Rated Classics: Dynamic threshold based on first page results, sort_by vote_count.desc, at least 20 count
            return fetchTopRatedClassics(origLang, genreId, page);
        }

        // Default fallback
        String gte = today.minusWeeks(4).toString();
        String lte = today.plusDays(7).toString();
        TmdbPageResponse<TmdbMovieResult> resp = tmdbService.discoverMovies(origLang, genreId, "vote_count.desc", gte, lte, null, page);
        sortResultsByVoteCountThenPopularity(resp);
        return resp;
    }

    private TmdbPageResponse<TmdbMovieResult> fetchTopRatedClassics(String origLang, String genreId, int page) {
        int safePage = Math.max(page, 1);
        int targetCount = safePage * DEFAULT_PAGE_SIZE;

        // 1. First hit TMDB API with sort_by vote_count.desc for page 1
        TmdbPageResponse<TmdbMovieResult> firstPageResp = tmdbService.discoverMovies(
                origLang,
                genreId,
                "vote_count.desc",
                null,
                null,
                null,
                1
        );

        if (firstPageResp == null || firstPageResp.getResults() == null || firstPageResp.getResults().isEmpty()) {
            TmdbPageResponse<TmdbMovieResult> empty = new TmdbPageResponse<>();
            empty.setPage(safePage);
            empty.setTotalPages(0);
            empty.setTotalResults(0);
            empty.setResults(List.of());
            return empty;
        }

        List<TmdbMovieResult> firstPageResults = firstPageResp.getResults();

        // Dynamic thresholds from first page results:
        // vote_count_filter = (last result of first page / 2)
        // vote_average_filter = (highest vote average of first page - 2.0)
        TmdbMovieResult lastMovie = firstPageResults.get(firstPageResults.size() - 1);
        long lastVoteCount = (lastMovie != null && lastMovie.getVoteCount() != null) ? lastMovie.getVoteCount() : 0L;
        long voteCountFilter = lastVoteCount / 2;

        double highestVoteAverage = firstPageResults.stream()
                .map(TmdbMovieResult::getVoteAverage)
                .filter(Objects::nonNull)
                .max(Double::compareTo)
                .orElse(0.0);
        double voteAverageFilter = highestVoteAverage - 2.0;

        List<TmdbMovieResult> accumulated = new ArrayList<>();
        Set<Long> seenIds = new HashSet<>();

        // Filter movies from first page
        for (TmdbMovieResult movie : firstPageResults) {
            if (movie != null && movie.getId() != null) {
                long vc = movie.getVoteCount() != null ? movie.getVoteCount() : 0L;
                double va = movie.getVoteAverage() != null ? movie.getVoteAverage() : 0.0;
                if (vc >= voteCountFilter && va >= voteAverageFilter && seenIds.add(movie.getId())) {
                    accumulated.add(movie);
                }
            }
        }

        int totalPages = firstPageResp.getTotalPages();
        int totalResults = firstPageResp.getTotalResults();
        int currentPage = 2;
        int maxPagesToFetch = 10;

        // Fetch subsequent pages until at least targetCount movies are accumulated
        while (accumulated.size() < targetCount && currentPage <= totalPages && currentPage <= maxPagesToFetch) {
            TmdbPageResponse<TmdbMovieResult> nextPageResp = tmdbService.discoverMovies(
                    origLang,
                    genreId,
                    "vote_count.desc",
                    null,
                    null,
                    null,
                    currentPage
            );

            if (nextPageResp == null || nextPageResp.getResults() == null || nextPageResp.getResults().isEmpty()) {
                break;
            }

            for (TmdbMovieResult movie : nextPageResp.getResults()) {
                if (movie != null && movie.getId() != null) {
                    long vc = movie.getVoteCount() != null ? movie.getVoteCount() : 0L;
                    double va = movie.getVoteAverage() != null ? movie.getVoteAverage() : 0.0;
                    if (vc >= voteCountFilter && va >= voteAverageFilter && seenIds.add(movie.getId())) {
                        accumulated.add(movie);
                    }
                }
            }

            currentPage++;
        }

        accumulated = rankTopRatedTmdbResultsByVoteCountBuckets(accumulated);

        int fromIndex = (safePage - 1) * DEFAULT_PAGE_SIZE;
        List<TmdbMovieResult> pagedResults;
        if (fromIndex >= accumulated.size()) {
            pagedResults = safePage == 1 ? accumulated : List.of();
        } else {
            int toIndex = Math.min(fromIndex + DEFAULT_PAGE_SIZE, accumulated.size());
            pagedResults = new ArrayList<>(accumulated.subList(fromIndex, toIndex));
        }

        TmdbPageResponse<TmdbMovieResult> result = new TmdbPageResponse<>();
        result.setPage(safePage);
        result.setTotalPages(totalPages);
        result.setTotalResults(totalResults > 0 ? totalResults : accumulated.size());
        result.setResults(pagedResults);
        return result;
    }

    /**
     * Ranks Top Rated Classics by dividing the vote_count range into 5 buckets (from min to max).
     * Inside each bucket, movies are sorted by vote_average desc (then vote_count desc, then popularity desc).
     * The final list is assembled in round-robin fashion: taking up to 3 movies from the highest vote_count
     * bucket (bucket 4), then 3 from bucket 3, ..., down to bucket 0, repeating until all movies are consumed.
     */
    public List<MovieCardDTO> rankTopRatedClassicsByVoteCountBuckets(List<MovieCardDTO> movies) {
        if (movies == null || movies.size() <= 1) {
            return movies != null ? movies : new ArrayList<>();
        }

        long minVoteCount = Long.MAX_VALUE;
        long maxVoteCount = Long.MIN_VALUE;
        for (MovieCardDTO movie : movies) {
            long vc = movie.getTmdbVoteCount() != null ? movie.getTmdbVoteCount() : 0L;
            if (vc < minVoteCount) minVoteCount = vc;
            if (vc > maxVoteCount) maxVoteCount = vc;
        }

        Comparator<MovieCardDTO> withinBucketComparator = Comparator
                .comparing((MovieCardDTO c) -> c.getTmdbVoteAverage() != null ? c.getTmdbVoteAverage() : 0.0, Comparator.reverseOrder())
                .thenComparing((MovieCardDTO c) -> c.getTmdbVoteCount() != null ? c.getTmdbVoteCount() : 0L, Comparator.reverseOrder())
                .thenComparing((MovieCardDTO c) -> c.getPopularity() != null ? c.getPopularity() : 0.0, Comparator.reverseOrder());

        if (maxVoteCount <= minVoteCount) {
            List<MovieCardDTO> sorted = new ArrayList<>(movies);
            sorted.sort(withinBucketComparator);
            return sorted;
        }

        double range = (double) (maxVoteCount - minVoteCount);
        double bucketWidth = range / 5.0;

        List<List<MovieCardDTO>> buckets = new ArrayList<>(5);
        for (int i = 0; i < 5; i++) {
            buckets.add(new ArrayList<>());
        }

        for (MovieCardDTO movie : movies) {
            long vc = movie.getTmdbVoteCount() != null ? movie.getTmdbVoteCount() : 0L;
            int bucketIndex;
            if (vc >= maxVoteCount) {
                bucketIndex = 4;
            } else {
                bucketIndex = (int) ((vc - minVoteCount) / bucketWidth);
                if (bucketIndex < 0) bucketIndex = 0;
                if (bucketIndex > 4) bucketIndex = 4;
            }
            buckets.get(bucketIndex).add(movie);
        }

        for (int i = 0; i < 5; i++) {
            buckets.get(i).sort(withinBucketComparator);
        }

        List<MovieCardDTO> ranked = new ArrayList<>(movies.size());
        int[] indices = new int[5];
        boolean hasMore = true;
        int batchSize = 3;

        while (hasMore) {
            hasMore = false;
            for (int b = 4; b >= 0; b--) {
                List<MovieCardDTO> bucket = buckets.get(b);
                int start = indices[b];
                if (start < bucket.size()) {
                    int end = Math.min(start + batchSize, bucket.size());
                    for (int i = start; i < end; i++) {
                        ranked.add(bucket.get(i));
                    }
                    indices[b] = end;
                    if (end < bucket.size()) {
                        hasMore = true;
                    }
                }
            }
        }

        return ranked;
    }

    private List<TmdbMovieResult> rankTopRatedTmdbResultsByVoteCountBuckets(List<TmdbMovieResult> results) {
        if (results == null || results.size() <= 1) {
            return results != null ? results : new ArrayList<>();
        }

        long minVoteCount = Long.MAX_VALUE;
        long maxVoteCount = Long.MIN_VALUE;
        for (TmdbMovieResult movie : results) {
            long vc = movie.getVoteCount() != null ? movie.getVoteCount() : 0L;
            if (vc < minVoteCount) minVoteCount = vc;
            if (vc > maxVoteCount) maxVoteCount = vc;
        }

        if (maxVoteCount <= minVoteCount) {
            List<TmdbMovieResult> sorted = new ArrayList<>(results);
            sorted.sort(VOTE_AVERAGE_THEN_VOTE_COUNT_COMPARATOR);
            return sorted;
        }

        double range = (double) (maxVoteCount - minVoteCount);
        double bucketWidth = range / 5.0;

        List<List<TmdbMovieResult>> buckets = new ArrayList<>(5);
        for (int i = 0; i < 5; i++) {
            buckets.add(new ArrayList<>());
        }

        for (TmdbMovieResult movie : results) {
            long vc = movie.getVoteCount() != null ? movie.getVoteCount() : 0L;
            int bucketIndex;
            if (vc >= maxVoteCount) {
                bucketIndex = 4;
            } else {
                bucketIndex = (int) ((vc - minVoteCount) / bucketWidth);
                if (bucketIndex < 0) bucketIndex = 0;
                if (bucketIndex > 4) bucketIndex = 4;
            }
            buckets.get(bucketIndex).add(movie);
        }

        for (int i = 0; i < 5; i++) {
            buckets.get(i).sort(VOTE_AVERAGE_THEN_VOTE_COUNT_COMPARATOR);
        }

        List<TmdbMovieResult> ranked = new ArrayList<>(results.size());
        int[] indices = new int[5];
        boolean hasMore = true;
        int batchSize = 3;

        while (hasMore) {
            hasMore = false;
            for (int b = 4; b >= 0; b--) {
                List<TmdbMovieResult> bucket = buckets.get(b);
                int start = indices[b];
                if (start < bucket.size()) {
                    int end = Math.min(start + batchSize, bucket.size());
                    for (int i = start; i < end; i++) {
                        ranked.add(bucket.get(i));
                    }
                    indices[b] = end;
                    if (end < bucket.size()) {
                        hasMore = true;
                    }
                }
            }
        }

        return ranked;
    }

    private void sortResultsByVoteCountThenPopularity(TmdbPageResponse<TmdbMovieResult> resp) {
        if (resp != null && resp.getResults() != null && !resp.getResults().isEmpty()) {
            Map<Long, TmdbMovieResult> distinct = new LinkedHashMap<>();
            for (TmdbMovieResult r : resp.getResults()) {
                if (r != null && r.getId() != null) {
                    distinct.putIfAbsent(r.getId(), r);
                }
            }
            List<TmdbMovieResult> sorted = new ArrayList<>(distinct.values());
            sorted.sort(VOTE_COUNT_THEN_POPULARITY_COMPARATOR);
            resp.setResults(sorted);
        }
    }

    public List<MovieCardDTO> mapToCards(List<TmdbMovieResult> results) {
        if (results == null) return List.of();

        Set<Long> seenIds = new HashSet<>();
        return results.stream()
                .filter(r -> r.getId() != null && r.getTitle() != null)
                .filter(r -> seenIds.add(r.getId()))
                .map(r -> {
                    Integer releaseYear = null;
                    if (r.getReleaseDate() != null && r.getReleaseDate().length() >= 4) {
                        try {
                            releaseYear = Integer.parseInt(r.getReleaseDate().substring(0, 4));
                        } catch (Exception ignored) {}
                    }

                    List<String> genres = List.of();
                    if (r.getGenreIds() != null) {
                        genres = r.getGenreIds().stream()
                                .map(id -> {
                                    if (id == null) return null;
                                    String name = tmdbService.resolveGenreName(id);
                                    if (name != null) return name;
                                    MovieGenre mg = MovieGenre.fromTmdbId(id);
                                    return mg != null ? mg.getDisplayName() : String.valueOf(id);
                                })
                                .filter(Objects::nonNull)
                                .toList();
                    }

                    CinemaIndustry industry = CinemaIndustry.fromString(r.getOriginalLanguage());

                    return MovieCardDTO.builder()
                            .id(r.getId())
                            .title(r.getTitle())
                            .overview(r.getOverview())
                            .posterUrl(tmdbService.buildPosterUrl(r.getPosterPath(), "w500"))
                            .backdropUrl(tmdbService.buildBackdropUrl(r.getBackdropPath(), "w1280"))
                            .releaseDate(r.getReleaseDate())
                            .releaseYear(releaseYear)
                            .originalLanguage(r.getOriginalLanguage())
                            .industry(industry != CinemaIndustry.ALL ? industry.name() : "GLOBAL")
                            .genres(genres)
                            .tmdbVoteAverage(r.getVoteAverage())
                            .tmdbVoteCount(r.getVoteCount())
                            .popularity(r.getPopularity())
                            .revenue(r.getRevenue())
                            .platformRating(0.0)
                            .platformRatingCount(0L)
                            .build();
                })
                .collect(Collectors.toList());
    }

    /**
     * Removes duplicate movies within a section preserving original rank / insertion order.
     */
    public static List<MovieCardDTO> deduplicateMovies(List<MovieCardDTO> movies) {
        if (movies == null || movies.isEmpty()) {
            return List.of();
        }
        Map<Long, MovieCardDTO> distinct = new LinkedHashMap<>();
        for (MovieCardDTO movie : movies) {
            if (movie != null && movie.getId() != null) {
                distinct.putIfAbsent(movie.getId(), movie);
            }
        }
        return new ArrayList<>(distinct.values());
    }

    private String formatSectionTitle(String section) {
        return switch (section) {
            case SECTION_TRENDING_TODAY -> "Trending Today";
            case SECTION_TRENDING_WEEK -> "Trending This Week";
            case SECTION_POPULAR -> "Popular Movies";
            case SECTION_TOP_RATED -> "Top Rated Classics";
            case SECTION_NOW_PLAYING -> "Now Playing in Theatres";
            default -> "Featured Movies";
        };
    }
}
