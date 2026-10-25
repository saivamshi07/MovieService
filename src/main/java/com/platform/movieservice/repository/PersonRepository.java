package com.platform.movieservice.repository;

import com.platform.movieservice.entity.Person;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface PersonRepository extends JpaRepository<Person, Long> {

    @Query("SELECT p FROM Person p WHERE LOWER(p.name) LIKE LOWER(CONCAT('%', :query, '%'))")
    Page<Person> searchByNameFuzzy(@Param("query") String query, Pageable pageable);

    @Query("SELECT p FROM Person p WHERE LOWER(p.name) LIKE LOWER(CONCAT('%', :query, '%')) AND p.knownForDepartment = 'Acting'")
    Page<Person> searchActorsFuzzy(@Param("query") String query, Pageable pageable);

    @Query("SELECT p FROM Person p WHERE LOWER(p.name) LIKE LOWER(CONCAT('%', :query, '%')) AND p.knownForDepartment != 'Acting'")
    Page<Person> searchCrewFuzzy(@Param("query") String query, Pageable pageable);
}
