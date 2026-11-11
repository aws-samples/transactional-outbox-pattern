// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package com.amazonaws.samples.service;

import com.amazonaws.samples.configuration.SqsProperties;
import com.amazonaws.samples.model.FlightOutbox;
import com.amazonaws.samples.repository.OutboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Limit;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchRequestEntry;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchResponse;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchResultEntry;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Outbox relay: publishes pending outbox rows to SQS and deletes the rows that SQS accepted.
 *
 * <p>Delivery is at-least-once. If the process dies (or the commit fails) after SQS accepted a
 * message, the row is sent again on the next poll with the same deduplication ID, which SQS FIFO
 * discards within its 5-minute deduplication window.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QueueService {

    private final OutboxRepository outboxRepository;
    private final SqsClient sqsClient;
    private final SqsProperties sqsProperties;
    private final QueueUrlResolver queueUrlResolver;

    /**
     * Each retry attempt runs in its own transaction (the retry advice wraps the transactional
     * advice), so row locks are not held during back-off.
     */
    @Scheduled(fixedDelayString = "${sqs.polling-ms}")
    @Retryable(maxAttemptsExpression = "${sqs.retry.max-attempts:5}",
            backoff = @Backoff(delayExpression = "${sqs.retry.delay-ms:2000}", multiplier = 2))
    @Transactional
    public void forwardEventsToSQS() {
        List<FlightOutbox> entities = outboxRepository.findAllByOrderByIdAsc(Limit.of(sqsProperties.batchSize()));
        if (entities.isEmpty()) {
            return;
        }

        List<SendMessageBatchRequestEntry> entries = entities.stream()
                .map(entity -> SendMessageBatchRequestEntry.builder()
                        .id(entity.getId().toString())
                        .messageGroupId(entity.getAggregateId())
                        .messageDeduplicationId(entity.getId().toString())
                        .messageBody(entity.getPayload())
                        .build())
                .toList();

        SendMessageBatchResponse response = sqsClient.sendMessageBatch(SendMessageBatchRequest.builder()
                .queueUrl(queueUrlResolver.queueUrl())
                .entries(entries)
                .build());

        Set<String> sentIds = response.successful().stream()
                .map(SendMessageBatchResultEntry::id)
                .collect(Collectors.toSet());
        List<FlightOutbox> sent = entities.stream()
                .filter(entity -> sentIds.contains(entity.getId().toString()))
                .toList();
        outboxRepository.deleteAllInBatch(sent);

        // Rows that SQS rejected stay in the outbox (the durable record) and are retried on the next
        // poll. Sender faults will not succeed on retry and need an operator; API input validation
        // prevents payloads that SQS cannot accept.
        response.failed().forEach(failure -> {
            if (failure.senderFault()) {
                log.error("Outbox event {} permanently rejected by SQS ({}): {}", failure.id(), failure.code(),
                        failure.message());
            }
            else {
                log.warn("Outbox event {} not accepted by SQS, will retry: {}", failure.id(), failure.code());
            }
        });
        log.debug("Relayed {} of {} outbox events", sent.size(), entities.size());
    }
}
