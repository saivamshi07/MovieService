package com.platform.movieservice.repository;

import com.platform.movieservice.entity.Movie;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;

@Repository
public interface MovieRepository extends JpaRepository<Movie, Long> {

    @Query("SELECT m FROM Movie m WHERE LOWER(m.title) LIKE LOWER(CONCAT('%', :query, '%')) ORDER BY m.platformRatingAverage DESC, m.tmdbVoteAverage DESC")
    Page<Movie> searchByTitleFuzzy(@Param("query") String query, Pageable pageable);

    Page<Movie> findByOriginalLanguageInOrderByTmdbVoteAverageDesc(Collection<String> languages, Pageable pageable);

    Page<Movie> findAllByOrderByTmdbVoteAverageDesc(Pageable pageable);
}
