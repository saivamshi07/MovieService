package com.platform.movieservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.movieservice.dto.tmdb.*;
import com.platform.movieservice.entity.*;
import com.platform.movieservice.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AsyncCatalogPersistenceService {

    private final MovieRepository movieRepository;
    private final PersonRepository personRepository;
    private final MovieCastRepository movieCastRepository;
    private final MovieCrewRepository movieCrewRepository;
    private final ObjectMapper objectMapper;

    @Async
    @Transactional
    public void persistMovieAndCredits(TmdbMovieDetail detail) {
        if (detail == null || detail.getId() == null) {
            return;
        }

        try {
            LocalDate releaseDate = null;
            if (detail.getReleaseDate() != null && !detail.getReleaseDate().isBlank()) {
                try {
                    releaseDate = LocalDate.parse(detail.getReleaseDate());
                } catch (Exception ignored) {}
            }

            String genresJson = null;
            if (detail.getGenres() != null) {
                try {
                    genresJson = objectMapper.writeValueAsString(detail.getGenres());
                } catch (Exception ignored) {}
            }

            Movie movie = movieRepository.findById(detail.getId())
                    .orElse(Movie.builder().id(detail.getId()).build());

            movie.setTitle(detail.getTitle());
            movie.setOriginalTitle(detail.getOriginalTitle());
            movie.setOverview(detail.getOverview());
            movie.setTagline(detail.getTagline());
            movie.setPosterPath(detail.getPosterPath());
            movie.setBackdropPath(detail.getBackdropPath());
            movie.setReleaseDate(releaseDate);
            movie.setRuntime(detail.getRuntime());
            movie.setOriginalLanguage(detail.getOriginalLanguage());
            movie.setTmdbVoteAverage(detail.getVoteAverage() != null ? BigDecimal.valueOf(detail.getVoteAverage()) : null);
            movie.setTmdbVoteCount(detail.getVoteCount());
            movie.setGenresJson(genresJson);
            movie.setStatus(detail.getStatus());

            movieRepository.save(movie);

            // Persist Credits
            if (detail.getCredits() != null) {
                // 1. Cast
                if (detail.getCredits().getCast() != null) {
                    movieCastRepository.deleteByMovieId(detail.getId());
                    List<MovieCast> castEntities = new ArrayList<>();
                    for (TmdbCastMember castMember : detail.getCredits().getCast()) {
                        if (castMember.getId() != null) {
                            castEntities.add(MovieCast.builder()
                                    .movieId(detail.getId())
                                    .personId(castMember.getId())
                                    .personName(castMember.getName())
                                    .characterName(castMember.getCharacter())
                                    .profilePath(castMember.getProfilePath())
                                    .castOrder(castMember.getOrder())
                                    .build());
                        }
                    }
                    movieCastRepository.saveAll(castEntities);
                }

                // 2. Crew
                if (detail.getCredits().getCrew() != null) {
                    movieCrewRepository.deleteByMovieId(detail.getId());
                    List<MovieCrew> crewEntities = new ArrayList<>();
                    for (TmdbCrewMember crewMember : detail.getCredits().getCrew()) {
                        if (crewMember.getId() != null) {
                            crewEntities.add(MovieCrew.builder()
                                    .movieId(detail.getId())
                                    .personId(crewMember.getId())
                                    .personName(crewMember.getName())
                                    .department(crewMember.getDepartment())
                                    .jobDesignation(crewMember.getJob())
                                    .profilePath(crewMember.getProfilePath())
                                    .build());
                        }
                    }
                    movieCrewRepository.saveAll(crewEntities);
                }
            }

            log.debug("Successfully persisted movie and credits for ID {}", detail.getId());
        } catch (Exception e) {
            log.error("Failed to asynchronously persist movie ID {}: {}", detail.getId(), e.getMessage());
        }
    }

    @Async
    @Transactional
    public void persistPerson(TmdbPersonDetail personDetail) {
        if (personDetail == null || personDetail.getId() == null) {
            return;
        }

        try {
            LocalDate birthday = null;
            if (personDetail.getBirthday() != null && !personDetail.getBirthday().isBlank()) {
                try {
                    birthday = LocalDate.parse(personDetail.getBirthday());
                } catch (Exception ignored) {}
            }

            LocalDate deathday = null;
            if (personDetail.getDeathday() != null && !personDetail.getDeathday().isBlank()) {
                try {
                    deathday = LocalDate.parse(personDetail.getDeathday());
                } catch (Exception ignored) {}
            }

            Person person = personRepository.findById(personDetail.getId())
                    .orElse(Person.builder().id(personDetail.getId()).build());

            person.setName(personDetail.getName());
            person.setBiography(personDetail.getBiography());
            person.setBirthday(birthday);
            person.setDeathday(deathday);
            person.setPlaceOfBirth(personDetail.getPlaceOfBirth());
            person.setProfilePath(personDetail.getProfilePath());
            person.setKnownForDepartment(personDetail.getKnownForDepartment());

            personRepository.save(person);
            log.debug("Successfully persisted person for ID {}", personDetail.getId());
        } catch (Exception e) {
            log.error("Failed to asynchronously persist person ID {}: {}", personDetail.getId(), e.getMessage());
        }
    }
}
