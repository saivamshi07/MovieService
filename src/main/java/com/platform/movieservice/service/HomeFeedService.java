package com.platform.movieservice.service;

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
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class HomeFeedService {

    private final TmdbService tmdbService;
    private final RedisTemplate<String, Object> redisTemplate;
    private final UserPreferenceService userPreferenceService;
    private final ProactivePrewarmService proactivePrewarmService;

    // Section constants
    public static final String SECTION_TRENDING_TODAY = "TRENDING_TODAY";
    public static final String SECTION_TRENDING_WEEK = "TRENDING_WEEK";
    public static final String SECTION_POPULAR = "POPULAR";
    public static final String SECTION_TOP_RATED = "TOP_RATED";
    public static final String SECTION_NOW_PLAYING = "NOW_PLAYING";

    private static final int DEFAULT_PAGE_SIZE = 20;

    // Cache key templates
    private static final String DEFAULT_POOL_KEY = "feed:default:%s:p:%d";
    private static final String INDUSTRY_POOL_KEY = "feed:pool:%s:ind:%s";
    private static final String GENRE_POOL_KEY = "feed:pool:%s:genre:%s";

    public SectionResponseDTO getSectionFeed(String section, int page) {
        return getSectionFeed(section, page, null, null);
    }

    /**
     * Resolves the feed section for caller.
     * 1. If explicit industry or genre query param is passed, serves targeted filtered feed (cached in Redis).
     * 2. If user is authenticated, composites user-preferred industry & genre pools with diversity shuffle.
     * 3. If guest / unauthenticated, returns the default global section.
     */
    public SectionResponseDTO getSectionFeed(String section, int page, String industryParam, String genreParam) {
        int safePage = Math.max(page, 1);

        // 1. Explicit query parameter takes highest priority
        if (StringUtils.hasText(industryParam) || StringUtils.hasText(genreParam)) {
            return getFilteredFeed(section, safePage, industryParam, genreParam);
        }

        UUID userId = UserContext.getUserId();
        if (userId != null) {
            UserPreferenceResponse preferences = userPreferenceService.getPreferences(userId);
            if (preferences != null && (!preferences.getIndustries().isEmpty() || !preferences.getGenres().isEmpty())) {
                return buildCompositeFeed(section, safePage, preferences);
            }
        }

        // Default global feed (Guest or User with no preferences)
        return getDefaultFeed(section, safePage);
    }

    @SuppressWarnings("unchecked")
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
            MovieGenre genre = MovieGenre.fromString(genreParam.trim());
            if (genre != null) {
                genreId = String.valueOf(genre.getTmdbId());
                genreName = genre.getDisplayName();
            } else {
                genreId = genreParam.trim();
                genreName = genreParam.trim();
            }
        }

        // Try Redis industry pool if single industry requested on page 1 with no extra genre filter
        if (indName != null && genreId == null && page == 1) {
            String indKey = String.format(INDUSTRY_POOL_KEY, section, indName);
            try {
                Object cached = redisTemplate.opsForValue().get(indKey);
                if (cached instanceof List<?>) {
                    List<MovieCardDTO> cards = (List<MovieCardDTO>) cached;
                    if (!cards.isEmpty()) {
                        return SectionResponseDTO.builder()
                                .section(section)
                                .title(formatSectionTitle(section) + " (" + indName + ")")
                                .page(1)
                                .totalPages(1)
                                .totalResults(cards.size())
                                .movies(cards)
                                .build();
                    }
                }
            } catch (Exception ignored) {}
        }

        // Fetch from TMDB discover
        TmdbPageResponse<TmdbMovieResult> tmdbPage = fetchFromTmdbForSection(section, langCode, genreId, page);
        List<MovieCardDTO> movieCards = mapToCards(tmdbPage.getResults());

        String titleSuffix = "";
        if (indName != null) titleSuffix += " - " + indName;
        if (genreName != null) titleSuffix += " (" + genreName + ")";

        return SectionResponseDTO.builder()
                .section(section)
                .title(formatSectionTitle(section) + titleSuffix)
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
                return (SectionResponseDTO) cached;
            }
        } catch (Exception e) {
            log.warn("Cache read error for {}: {}", cacheKey, e.getMessage());
        }

        // Cache miss -> Fetch directly from TMDB
        TmdbPageResponse<TmdbMovieResult> tmdbPage = fetchFromTmdbForSection(section, null, null, page);
        List<MovieCardDTO> movieCards = mapToCards(tmdbPage.getResults());

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
     * Composites candidates from atomic industry and genre pools stored in Redis,
     * deduplicates by movieId, applies a diversity-preserving shuffle, and slices to page size.
     */
    @SuppressWarnings("unchecked")
    private SectionResponseDTO buildCompositeFeed(String section, int page, UserPreferenceResponse preferences) {
        List<MovieCardDTO> candidatePool = new ArrayList<>();

        // 1. Pull candidates from preferred industry pools
        for (String indCode : preferences.getIndustries()) {
            String indKey = String.format(INDUSTRY_POOL_KEY, section, indCode);
            try {
                Object cached = redisTemplate.opsForValue().get(indKey);
                if (cached instanceof List<?>) {
                    candidatePool.addAll((List<MovieCardDTO>) cached);
                }
            } catch (Exception ignored) {}
        }

        // 2. Pull candidates from preferred genre pools
        for (String genreCode : preferences.getGenres()) {
            String genreKey = String.format(GENRE_POOL_KEY, section, genreCode);
            try {
                Object cached = redisTemplate.opsForValue().get(genreKey);
                if (cached instanceof List<?>) {
                    candidatePool.addAll((List<MovieCardDTO>) cached);
                }
            } catch (Exception ignored) {}
        }

        // 3. Fallback to default pool if atomic pools are empty/not yet warmed
        if (candidatePool.isEmpty()) {
            return getDefaultFeed(section, page);
        }

        // 4. De-duplicate by Movie ID (preserving high-relevance matches)
        Map<Long, MovieCardDTO> distinctMovies = new LinkedHashMap<>();
        for (MovieCardDTO card : candidatePool) {
            distinctMovies.putIfAbsent(card.getId(), card);
        }

        List<MovieCardDTO> blendedList = new ArrayList<>(distinctMovies.values());

        // 5. Diversity Shuffle: Seeded with (page + day) so pagination is stable during browsing
        long seed = System.currentTimeMillis() / (1000 * 60 * 30); // Stable for 30 minutes
        Collections.shuffle(blendedList, new Random(seed + page));

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
     * Application Startup Event: Loads all default and atomic pools into Redis immediately on boot.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        log.info("Movie Service started: warming home feed pools in Redis...");
        refreshAllFeeds();
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
            List<MovieCardDTO> defaultCards = mapToCards(defaultPage.getResults());
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

            // 2. Refresh Atomic Industry Pools (5 Supported Industries)
            for (CinemaIndustry industry : CinemaIndustry.values()) {
                if (industry == CinemaIndustry.ALL) continue;

                TmdbPageResponse<TmdbMovieResult> indPage = fetchFromTmdbForSection(section, industry.getOriginalLanguageCode(), null, 1);
                List<MovieCardDTO> indCards = mapToCards(indPage.getResults());
                redisTemplate.opsForValue().set(
                        String.format(INDUSTRY_POOL_KEY, section, industry.name()),
                        indCards,
                        Duration.ofHours(8)
                );
            }

            // 3. Refresh Atomic Genre Pools (11 Supported Genres)
            for (MovieGenre genre : MovieGenre.values()) {
                TmdbPageResponse<TmdbMovieResult> genrePage = fetchFromTmdbForSection(section, null, String.valueOf(genre.getTmdbId()), 1);
                List<MovieCardDTO> genreCards = mapToCards(genrePage.getResults());
                redisTemplate.opsForValue().set(
                        String.format(GENRE_POOL_KEY, section, genre.name()),
                        genreCards,
                        Duration.ofHours(8)
                );
            }

            log.debug("Successfully refreshed Redis pools for section: {}", section);
        } catch (Exception e) {
            log.error("Failed to refresh section {}: {}", section, e.getMessage());
        }
    }

    private TmdbPageResponse<TmdbMovieResult> fetchFromTmdbForSection(String section, String origLang, String genreId, int page) {
        if (origLang != null || genreId != null) {
            LocalDate today = LocalDate.now();
            return switch (section) {
                case SECTION_TRENDING_TODAY -> {
                    // Trending Today: High momentum releases within recent 6 months
                    String gte = today.minusMonths(6).toString();
                    String lte = today.toString();
                    yield tmdbService.discoverMovies(origLang, genreId, "popularity.desc", gte, lte, null, page);
                }
                case SECTION_TRENDING_WEEK -> {
                    // Trending This Week: Releases in the last 2 years with steady popularity
                    String gte = today.minusYears(2).toString();
                    String lte = today.toString();
                    yield tmdbService.discoverMovies(origLang, genreId, "popularity.desc", gte, lte, null, page);
                }
                case SECTION_POPULAR -> {
                    // Popular: All-time popularity across years
                    yield tmdbService.discoverMovies(origLang, genreId, "popularity.desc", null, null, null, page);
                }
                case SECTION_TOP_RATED -> {
                    // Top Rated: Highest vote average with minimum vote threshold
                    yield tmdbService.discoverMovies(origLang, genreId, "vote_average.desc", null, null, 50, page);
                }
                case SECTION_NOW_PLAYING -> {
                    // Now Playing: Current theatrical release window (-6 weeks to +1 week)
                    String gte = today.minusWeeks(6).toString();
                    String lte = today.plusWeeks(1).toString();
                    yield tmdbService.discoverMovies(origLang, genreId, "popularity.desc", gte, lte, null, page);
                }
                default -> tmdbService.discoverMovies(origLang, genreId, "popularity.desc", null, null, null, page);
            };
        }

        return switch (section) {
            case SECTION_TRENDING_TODAY -> tmdbService.fetchTrendingMovies("day", page);
            case SECTION_TRENDING_WEEK -> tmdbService.fetchTrendingMovies("week", page);
            case SECTION_POPULAR -> tmdbService.fetchPopularMovies(page);
            case SECTION_TOP_RATED -> tmdbService.fetchTopRatedMovies(page);
            case SECTION_NOW_PLAYING -> tmdbService.fetchNowPlayingMovies(page);
            default -> tmdbService.fetchTrendingMovies("day", page);
        };
    }

    public List<MovieCardDTO> mapToCards(List<TmdbMovieResult> results) {
        if (results == null) return List.of();

        return results.stream()
                .filter(r -> r.getId() != null && r.getTitle() != null)
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
                                .map(MovieGenre::fromTmdbId)
                                .filter(Objects::nonNull)
                                .map(MovieGenre::getDisplayName)
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
                            .platformRating(0.0)
                            .platformRatingCount(0L)
                            .build();
                })
                .collect(Collectors.toList());
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
