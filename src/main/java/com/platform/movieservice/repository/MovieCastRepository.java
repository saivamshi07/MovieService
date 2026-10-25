package com.platform.movieservice.repository;

import com.platform.movieservice.entity.MovieCast;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MovieCastRepository extends JpaRepository<MovieCast, Long> {

    List<MovieCast> findByMovieIdOrderByCastOrderAsc(Long movieId);

    List<MovieCast> findByPersonId(Long personId);

    void deleteByMovieId(Long movieId);
}
