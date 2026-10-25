package com.platform.movieservice.repository;

import com.platform.movieservice.entity.MovieCrew;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MovieCrewRepository extends JpaRepository<MovieCrew, Long> {

    List<MovieCrew> findByMovieId(Long movieId);

    List<MovieCrew> findByPersonId(Long personId);

    void deleteByMovieId(Long movieId);
}
