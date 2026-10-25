package com.platform.movieservice.repository;

import com.platform.movieservice.entity.OutboxEvent;
import com.platform.movieservice.entity.enums.OutboxStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    List<OutboxEvent> findTop50ByStatusOrderByCreatedAtAsc(OutboxStatus status);

    long countByStatus(OutboxStatus status);
}
