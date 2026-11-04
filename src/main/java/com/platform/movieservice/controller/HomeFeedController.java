package com.platform.movieservice.controller;

import com.platform.movieservice.dto.response.MovieCardDTO;
import com.platform.movieservice.dto.response.SectionResponseDTO;
import com.platform.movieservice.service.HomeFeedService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/movies/home")
@RequiredArgsConstructor
public class HomeFeedController {

    private final HomeFeedService homeFeedService;

    @GetMapping("/trending-today")
    public ResponseEntity<SectionResponseDTO> getTrendingToday(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(required = false) String industry,
            @RequestParam(required = false) String genre) {
        return ResponseEntity.ok(homeFeedService.getSectionFeed(HomeFeedService.SECTION_TRENDING_TODAY, page, industry, genre));
    }

    @GetMapping("/trending-week")
    public ResponseEntity<SectionResponseDTO> getTrendingWeek(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(required = false) String industry,
            @RequestParam(required = false) String genre) {
        return ResponseEntity.ok(homeFeedService.getSectionFeed(HomeFeedService.SECTION_TRENDING_WEEK, page, industry, genre));
    }

    @GetMapping("/popular/top")
    public ResponseEntity<List<MovieCardDTO>> getTopPopular(
            @RequestParam String industry,
            @RequestParam String genre,
            @RequestParam(required = false) Long excludeMovieId,
            @RequestParam(required = false) Long excludeId) {
        Long exclude = excludeMovieId != null ? excludeMovieId : excludeId;
        return ResponseEntity.ok(homeFeedService.getTopPopularMovies(industry, genre, exclude));
    }

    @GetMapping("/popular")
    public ResponseEntity<SectionResponseDTO> getPopular(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(required = false) String industry,
            @RequestParam(required = false) String genre,
            @RequestParam(required = false) Long excludeMovieId,
            @RequestParam(required = false) Long excludeId) {
        Long exclude = excludeMovieId != null ? excludeMovieId : excludeId;
        return ResponseEntity.ok(homeFeedService.getSectionFeed(HomeFeedService.SECTION_POPULAR, page, industry, genre, exclude));
    }

    @GetMapping("/top-rated")
    public ResponseEntity<SectionResponseDTO> getTopRated(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(required = false) String industry,
            @RequestParam(required = false) String genre) {
        return ResponseEntity.ok(homeFeedService.getSectionFeed(HomeFeedService.SECTION_TOP_RATED, page, industry, genre));
    }

    @GetMapping("/now-playing")
    public ResponseEntity<SectionResponseDTO> getNowPlaying(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(required = false) String industry,
            @RequestParam(required = false) String genre) {
        return ResponseEntity.ok(homeFeedService.getSectionFeed(HomeFeedService.SECTION_NOW_PLAYING, page, industry, genre));
    }

    /**
     * Unified Home Feed endpoint: aggregates the 5 sections in a single response for rapid page load.
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> getUnifiedHomeFeed() {
        Map<String, Object> feed = new LinkedHashMap<>();
        feed.put("trendingToday", homeFeedService.getSectionFeed(HomeFeedService.SECTION_TRENDING_TODAY, 1));
        feed.put("trendingWeek", homeFeedService.getSectionFeed(HomeFeedService.SECTION_TRENDING_WEEK, 1));
        feed.put("popular", homeFeedService.getSectionFeed(HomeFeedService.SECTION_POPULAR, 1));
        feed.put("topRated", homeFeedService.getSectionFeed(HomeFeedService.SECTION_TOP_RATED, 1));
        feed.put("nowPlaying", homeFeedService.getSectionFeed(HomeFeedService.SECTION_NOW_PLAYING, 1));
        return ResponseEntity.ok(feed);
    }
}
