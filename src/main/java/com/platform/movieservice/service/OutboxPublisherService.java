package com.platform.movieservice.service;

import com.platform.movieservice.entity.OutboxEvent;
import com.platform.movieservice.entity.enums.OutboxStatus;
import com.platform.movieservice.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxPublisherService {

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    @Value("${kafka.topics.movie-rating:movie.rating.v1}")
    private String movieRatingTopic;

    @Value("${outbox.publisher.enabled:true}")
    private boolean outboxEnabled;

    @Scheduled(fixedDelayString = "${outbox.publisher.fixed-delay-ms:2000}")
    @Transactional
    public void publishPendingEvents() {
        if (!outboxEnabled) {
            return;
        }

        List<OutboxEvent> pendingEvents = outboxEventRepository.findTop50ByStatusOrderByCreatedAtAsc(OutboxStatus.PENDING);
        if (pendingEvents.isEmpty()) {
            return;
        }

        for (OutboxEvent event : pendingEvents) {
            try {
                // Publish with aggregateId (userId) as the partition key for strict ordering!
                kafkaTemplate.send(movieRatingTopic, event.getAggregateId(), event.getPayload())
                        .whenComplete((result, ex) -> {
                            if (ex == null) {
                                event.setStatus(OutboxStatus.PUBLISHED);
                                event.setProcessedAt(Instant.now());
                                outboxEventRepository.save(event);
                                log.debug("Published outbox event {} to Kafka topic {}", event.getId(), movieRatingTopic);
                            } else {
                                log.warn("Failed to publish outbox event {}: {}", event.getId(), ex.getMessage());
                                event.setRetryCount(event.getRetryCount() + 1);
                                if (event.getRetryCount() >= 5) {
                                    event.setStatus(OutboxStatus.FAILED);
                                }
                                outboxEventRepository.save(event);
                            }
                        });
            } catch (Exception e) {
                log.error("Exception sending event {} to Kafka: {}", event.getId(), e.getMessage());
                event.setRetryCount(event.getRetryCount() + 1);
                if (event.getRetryCount() >= 5) {
                    event.setStatus(OutboxStatus.FAILED);
                }
                outboxEventRepository.save(event);
            }
        }
    }
}
