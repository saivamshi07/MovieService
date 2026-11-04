package com.platform.movieservice.service;

import com.platform.movieservice.dto.tmdb.*;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * High-fidelity fallback catalog of real TMDB movies across industries (Hollywood, Tollywood, Bollywood)
 * and genres (Action, Sci-Fi, Drama, Thriller, Comedy) used when no TMDB API key is configured
 * or when TMDB API is temporarily unreachable.
 */
public final class MockTmdbDataProvider {

    private MockTmdbDataProvider() {}

    private static final List<TmdbMovieResult> CATALOG = List.of(
            createMovie(550L, "Fight Club",
                    "An insomniac office worker and a devil-may-care soap maker form an underground fight club that evolves into much more.",
                    "/pB8BM7pdSp6B6Ih7QZ4DrQ3PmJK.jpg", "/hZkgoQYus5vegHoetLkCJzb17zJ.jpg",
                    "1999-10-15", "en", 8.43, 28000L, 85.5, List.of(18, 53)),

            createMovie(157336L, "Interstellar",
                    "The adventures of a group of explorers who make use of a newly discovered wormhole to surpass the limitations on human space travel.",
                    "/gEU2QniE6E77NI6lCU6MxlNBvIx.jpg", "/rAiYTggpH9AcYq0q7fVzDqGk6cK.jpg",
                    "2014-11-05", "en", 8.44, 34000L, 120.4, List.of(12, 18, 878)),

            createMovie(1011985L, "Pushpa 2: The Rule",
                    "Pushpa Raj continues to consolidate his grip over the red sandalwood syndicate while facing intensifying law enforcement pressure.",
                    "/pushpa2.jpg", "/pushpa2_bg.jpg",
                    "2024-12-05", "te", 8.10, 12000L, 95.0, List.of(28, 53, 18)),

            createMovie(969681L, "Devara: Part 1",
                    "A fearless man embarks on an arduous voyage in a coastal landscape to safeguard his people from treachery.",
                    "/devara.jpg", "/devara_bg.jpg",
                    "2024-09-27", "te", 7.50, 5000L, 65.2, List.of(28, 18)),

            createMovie(1022789L, "Kalki 2898 AD",
                    "Set in a post-apocalyptic world in the year 2898 AD, a modern-day avatar of Vishnu descends to protect the world.",
                    "/kalki.jpg", "/kalki_bg.jpg",
                    "2024-06-27", "te", 8.00, 8000L, 88.0, List.of(878, 28)),

            createMovie(980078L, "Hanu-Man",
                    "An ordinary young man gets superpowers from an ancient totem and must defend his village against a villain.",
                    "/hanuman.jpg", "/hanuman_bg.jpg",
                    "2024-01-12", "te", 7.80, 6000L, 72.1, List.of(14, 28)),

            createMovie(792307L, "Salaar: Part 1 – Ceasefire",
                    "A gang leader tries to keep a promise made to his dying friend and takes on other criminal syndicates in Khansaar.",
                    "/salaar.jpg", "/salaar_bg.jpg",
                    "2023-12-22", "te", 7.60, 7000L, 80.5, List.of(28, 53)),

            createMovie(1151534L, "Hi Nanna",
                    "A single father’s world revolves around his 6-year-old daughter until an unexpected encounter begins to reshape their lives.",
                    "/hinanna.jpg", "/hinanna_bg.jpg",
                    "2023-12-07", "te", 8.20, 5000L, 58.0, List.of(18, 10749)),

            createMovie(579974L, "RRR",
                    "A fictional history of two legendary revolutionaries' journey away from home before they began fighting for their country in the 1920s.",
                    "/wE0noFUqFvgH52mG4eD9mGvO3uN.jpg", "/ozkegRoxb7QfN3X9aX44uWp144I.jpg",
                    "2022-03-24", "te", 7.82, 20000L, 110.0, List.of(28, 18)),

            createMovie(350312L, "Baahubali 2: The Conclusion",
                    "When Shiva, the son of Bahubali, learns about his heritage, he begins to look for answers while vengeance looms.",
                    "/baahubali2.jpg", "/baahubali2_bg.jpg",
                    "2017-04-28", "te", 8.25, 19000L, 105.0, List.of(28, 18)),

            createMovie(256040L, "Baahubali: The Beginning",
                    "In the kingdom of Mahishmati, an adventurous young man becomes embroiled in a bitter dynastic struggle.",
                    "/baahubali1.jpg", "/baahubali1_bg.jpg",
                    "2015-07-10", "te", 8.05, 17000L, 98.0, List.of(28, 18)),

            createMovie(584962L, "Jersey",
                    "A failed cricketer in his mid-thirties decides to revive his cricketing career despite everyone around him doubting his ability.",
                    "/jersey.jpg", "/jersey_bg.jpg",
                    "2019-04-19", "te", 8.30, 11000L, 75.0, List.of(18)),

            createMovie(872585L, "Oppenheimer",
                    "The story of J. Robert Oppenheimer's role in the development of the atomic bomb during World War II.",
                    "/8Gxv8gSFCU0XGDykEGv7zR1n2ua.jpg", "/fm6KqXpk3M2HVveHwCrBSSBaO0V.jpg",
                    "2023-07-19", "en", 8.11, 15000L, 95.0, List.of(18, 36)),

            createMovie(155L, "The Dark Knight",
                    "Batman raises the stakes in his war on crime with the help of Lt. Jim Gordon and District Attorney Harvey Dent.",
                    "/qJ2tW6WMUDux911r6m7haRef0WH.jpg", "/dqK9Hag1054tghRQSqLSPoILQ96.jpg",
                    "2008-07-16", "en", 8.51, 32000L, 140.0, List.of(18, 28, 80, 53)),

            createMovie(693134L, "Dune: Part Two",
                    "Follow the mythic journey of Paul Atreides as he unites with Chani and the Fremen while seeking revenge against the conspirators.",
                    "/1pdfLvkbY9ohJlCjQH2CZjjYVvJ.jpg", "/xOMo8BRK7PfcJv9JCnx7s520DRq.jpg",
                    "2024-02-27", "en", 8.17, 10000L, 89.0, List.of(878, 12)),

            createMovie(238L, "The Godfather",
                    "Spanning the years 1945 to 1955, a chronicle of the fictional Italian-American Corleone crime family.",
                    "/3bhkrj58Vtu7enYsRolD1fZdja1.jpg", "/tmU7GeKVybMWFButWEGl2M4GeiP.jpg",
                    "1972-03-14", "en", 8.69, 21000L, 115.0, List.of(18, 80)),

            createMovie(27205L, "Inception",
                    "Cobb, a skilled thief who commits corporate espionage by infiltrating the subconscious of his targets, is offered a chance to regain his old life.",
                    "/oYuLEt3zVCKq57qu2F8dT7NIa6f.jpg", "/s3BrRGB1iav7gFOCNx3H31MoES.jpg",
                    "2010-07-15", "en", 8.37, 36000L, 130.0, List.of(28, 878, 12)),

            createMovie(705996L, "Kantara",
                    "When greed paves the way for betrayal, scheming and murder, a young tribal reluctantly dons the traditions of his ancestors to seek justice.",
                    "/5VBCoT0y70hR3M7q610X58u53wG.jpg", "/q9qD5fXk679A8Vv8n0L9rP39Q9X.jpg",
                    "2022-09-30", "te", 7.64, 9000L, 78.0, List.of(28, 18, 53)),

            createMovie(866398L, "The Beekeeper",
                    "One man's campaign for vengeance takes on national stakes after he is revealed to be a former operative of a powerful clandestine organization.",
                    "/A7EByudX0eOzlkQ2FIbogzyazm2.jpg", "/4woSOUD0equvdZvwhodHIoqG2Op.jpg",
                    "2024-01-08", "en", 7.42, 6000L, 62.0, List.of(28, 53)),

            createMovie(787699L, "Wonka",
                    "Willy Wonka – chock-full of ideas and determined to change the world one delectable bite at a time.",
                    "/qhb1qYHeY97Ol5zO00E6P6t8UeJ.jpg", "/yyFcJnRvzzNj9pd4fe0i26KqVRU.jpg",
                    "2023-12-06", "en", 7.18, 7000L, 68.0, List.of(35, 10751, 14)),

            createMovie(939243L, "Sonic the Hedgehog 3",
                    "Sonic, Knuckles, and Tails reunite against a powerful new adversary, Shadow, a mysterious villain with powers unlike anything they have faced.",
                    "/d8Ryb8AunYAuycPtY2Pk5Rwh1xH.jpg", "/zOpe0e7Ek482Ty5Xuv2GUT9eoUm.jpg",
                    "2024-12-19", "en", 7.75, 8000L, 77.0, List.of(28, 878, 35, 10751))
    );

    public static List<TmdbGenre> getFallbackGenres() {
        return List.of(
                createGenre(28, "Action"),
                createGenre(12, "Adventure"),
                createGenre(16, "Animation"),
                createGenre(35, "Comedy"),
                createGenre(80, "Crime"),
                createGenre(99, "Documentary"),
                createGenre(18, "Drama"),
                createGenre(10751, "Family"),
                createGenre(14, "Fantasy"),
                createGenre(36, "History"),
                createGenre(27, "Horror"),
                createGenre(10402, "Music"),
                createGenre(9648, "Mystery"),
                createGenre(10749, "Romance"),
                createGenre(878, "Science Fiction"),
                createGenre(10770, "TV Movie"),
                createGenre(53, "Thriller"),
                createGenre(10752, "War"),
                createGenre(37, "Western")
        );
    }

    private static TmdbGenre createGenre(int id, String name) {
        TmdbGenre g = new TmdbGenre();
        g.setId(id);
        g.setName(name);
        return g;
    }

    public static TmdbPageResponse<TmdbMovieResult> getFallbackFeed(String section, String origLang, String genreId, int page) {
        List<TmdbMovieResult> filtered = new ArrayList<>(CATALOG);

        if (origLang != null && !origLang.isBlank()) {
            List<TmdbMovieResult> langMatches = CATALOG.stream()
                    .filter(m -> origLang.equalsIgnoreCase(m.getOriginalLanguage()))
                    .toList();
            if (!langMatches.isEmpty()) {
                filtered = new ArrayList<>(langMatches);
            }
        }

        if (genreId != null && !genreId.isBlank()) {
            try {
                int targetGenre = Integer.parseInt(genreId.trim());
                List<TmdbMovieResult> genreFiltered = filtered.stream()
                        .filter(m -> m.getGenreIds() != null && m.getGenreIds().contains(targetGenre))
                        .toList();
                if (!genreFiltered.isEmpty()) {
                    filtered = genreFiltered;
                }
            } catch (Exception ignored) {}
        }

        if (section != null) {
            if (section.contains("TRENDING_TODAY") || section.equals("TRENDING") || section.contains("TRENDING_WEEK")
                    || section.contains("NOW_PLAYING") || section.contains("POPULAR")) {
                filtered = filtered.stream()
                        .sorted(Comparator.comparing((TmdbMovieResult m) -> m.getVoteCount() != null ? m.getVoteCount() : 0L, Comparator.reverseOrder())
                                .thenComparing((TmdbMovieResult m) -> m.getPopularity() != null ? m.getPopularity() : 0.0, Comparator.reverseOrder()))
                        .toList();
            } else if (section.contains("TOP_RATED")) {
                List<TmdbMovieResult> sorted = filtered.stream()
                        .sorted(Comparator.comparing((TmdbMovieResult m) -> m.getVoteCount() != null ? m.getVoteCount() : 0L, Comparator.reverseOrder())
                                .thenComparing((TmdbMovieResult m) -> m.getPopularity() != null ? m.getPopularity() : 0.0, Comparator.reverseOrder()))
                        .toList();
                if (!sorted.isEmpty()) {
                    long lastVoteCount = sorted.get(sorted.size() - 1).getVoteCount() != null ? sorted.get(sorted.size() - 1).getVoteCount() : 0L;
                    long voteCountFilter = lastVoteCount / 2;
                    double highestVoteAverage = sorted.stream()
                            .map(TmdbMovieResult::getVoteAverage)
                            .filter(Objects::nonNull)
                            .max(Double::compareTo)
                            .orElse(0.0);
                    double voteAverageFilter = highestVoteAverage - 2.0;

                    filtered = sorted.stream()
                            .filter(m -> (m.getVoteCount() != null ? m.getVoteCount() : 0L) >= voteCountFilter
                                    && (m.getVoteAverage() != null ? m.getVoteAverage() : 0.0) >= voteAverageFilter)
                            .sorted(Comparator.comparing((TmdbMovieResult m) -> m.getVoteAverage() != null ? m.getVoteAverage() : 0.0, Comparator.reverseOrder())
                                    .thenComparing((TmdbMovieResult m) -> m.getVoteCount() != null ? m.getVoteCount() : 0L, Comparator.reverseOrder())
                                    .thenComparing((TmdbMovieResult m) -> m.getPopularity() != null ? m.getPopularity() : 0.0, Comparator.reverseOrder()))
                            .toList();
                } else {
                    filtered = sorted;
                }
            }
        }

        TmdbPageResponse<TmdbMovieResult> response = new TmdbPageResponse<>();
        response.setPage(Math.max(1, page));
        response.setTotalPages(1);
        response.setTotalResults(filtered.size());
        response.setResults(filtered);
        return response;
    }

    public static TmdbMovieDetail getFallbackDetail(Long movieId) {
        TmdbMovieResult matched = CATALOG.stream()
                .filter(m -> m.getId().equals(movieId))
                .findFirst()
                .orElse(CATALOG.get(0));

        TmdbMovieDetail detail = new TmdbMovieDetail();
        detail.setId(matched.getId());
        detail.setTitle(matched.getTitle());
        detail.setOverview(matched.getOverview());
        detail.setPosterPath(matched.getPosterPath());
        detail.setBackdropPath(matched.getBackdropPath());
        detail.setReleaseDate(matched.getReleaseDate());
        detail.setOriginalLanguage(matched.getOriginalLanguage());
        detail.setVoteAverage(matched.getVoteAverage());
        detail.setRuntime(148);
        detail.setStatus("Released");

        // Credits
        TmdbCredits credits = new TmdbCredits();
        credits.setCast(List.of(
                createCastMember(1001L, "Brad Pitt", "Tyler Durden", "/cckcYc2v0yh1tc9QjRelptsqpHW.jpg", 1),
                createCastMember(1002L, "Edward Norton", "The Narrator", "/5XBzD5WuTyVQ2w4VI1sqLsLJqNa.jpg", 2)
        ));
        credits.setCrew(List.of(
                createCrewMember(2001L, "David Fincher", "Directing", "Director", "/Director.jpg"),
                createCrewMember(2002L, "Chuck Palahniuk", "Writing", "Novel", "/Writer.jpg"),
                createCrewMember(2003L, "Jim Uhls", "Writing", "Screenplay", "/Writer2.jpg"),
                createCrewMember(2004L, "Art Linson", "Production", "Producer", "/Producer.jpg"),
                createCrewMember(2005L, "The Dust Brothers", "Sound", "Original Music Composer", "/Music.jpg")
        ));
        detail.setCredits(credits);
        return detail;
    }

    public static TmdbPersonDetail getFallbackPerson(Long personId) {
        TmdbPersonDetail person = new TmdbPersonDetail();
        person.setId(personId != null ? personId : 1001L);
        person.setName("Brad Pitt");
        person.setBiography("William Bradley Pitt is an American actor and film producer. He has received multiple accolades, including an Academy Award, a British Academy Film Award, and two Golden Globe Awards.");
        person.setBirthday("1963-12-18");
        person.setPlaceOfBirth("Shawnee, Oklahoma, USA");
        person.setProfilePath("/cckcYc2v0yh1tc9QjRelptsqpHW.jpg");
        person.setKnownForDepartment("Acting");

        TmdbPersonMovieCredits credits = new TmdbPersonMovieCredits();
        credits.setCast(List.of(CATALOG.get(0), CATALOG.get(4)));
        credits.setCrew(List.of());
        person.setMovieCredits(credits);
        return person;
    }

    private static TmdbMovieResult createMovie(Long id, String title, String overview, String poster,
                                                String backdrop, String date, String lang, double vote, Long voteCount, Double popularity, List<Integer> genres) {
        return createMovie(id, title, overview, poster, backdrop, date, lang, vote, voteCount, popularity, 100000000L, genres);
    }

    private static TmdbMovieResult createMovie(Long id, String title, String overview, String poster,
                                                String backdrop, String date, String lang, double vote, Long voteCount, Double popularity, Long revenue, List<Integer> genres) {
        TmdbMovieResult m = new TmdbMovieResult();
        m.setId(id);
        m.setTitle(title);
        m.setOverview(overview);
        m.setPosterPath(poster);
        m.setBackdropPath(backdrop);
        m.setReleaseDate(date);
        m.setOriginalLanguage(lang);
        m.setVoteAverage(vote);
        m.setVoteCount(voteCount != null ? voteCount : 15000L);
        m.setPopularity(popularity != null ? popularity : 50.0);
        m.setRevenue(revenue != null ? revenue : 0L);
        m.setGenreIds(genres);
        return m;
    }

    private static TmdbCastMember createCastMember(Long id, String name, String character, String profile, int order) {
        TmdbCastMember c = new TmdbCastMember();
        c.setId(id);
        c.setName(name);
        c.setCharacter(character);
        c.setProfilePath(profile);
        c.setOrder(order);
        return c;
    }

    private static TmdbCrewMember createCrewMember(Long id, String name, String department, String job, String profile) {
        TmdbCrewMember c = new TmdbCrewMember();
        c.setId(id);
        c.setName(name);
        c.setDepartment(department);
        c.setJob(job);
        c.setProfilePath(profile);
        return c;
    }
}
