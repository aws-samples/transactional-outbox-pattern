// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package com.amazonaws.samples.relay;

import com.amazonaws.samples.service.QueueUrlResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.InvalidMessageContentsException;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import tools.jackson.databind.ObjectMapper;

/**
 * Publishes captured flight changes to the SQS FIFO queue. Failures propagate to the caller (and
 * are retried), except a message SQS will never accept, which is logged and skipped like any other
 * unpublishable record so it cannot block the shard.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FlightEventPublisher {

    private final SqsClient sqsClient;
    private final QueueUrlResolver queueUrlResolver;
    private final ObjectMapper objectMapper;

    /** @return {@code true} if SQS accepted the event, {@code false} if it was rejected as invalid */
    public boolean publish(FlightChangeEvent event) {
        try {
            send(event);
            return true;
        }
        catch (InvalidMessageContentsException ex) {
            log.error("SQS permanently rejected change {} for flight {}, skipping it: {}", event.eventId(),
                    event.flight().getId(), ex.awsErrorDetails().errorMessage());
            return false;
        }
    }

    private void send(FlightChangeEvent event) {
        sqsClient.sendMessage(SendMessageRequest.builder()
                .queueUrl(queueUrlResolver.queueUrl())
                .messageBody(objectMapper.writeValueAsString(event.flight()))
                // Per-flight ordering; the change record ID deduplicates Kinesis redeliveries.
                .messageGroupId(event.flight().getId().toString())
                .messageDeduplicationId(event.eventId())
                .build());
    }
}
