// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package com.amazonaws.samples;

import com.amazonaws.samples.controller.FlightController;
import com.amazonaws.samples.model.Flight;
import com.amazonaws.samples.model.FlightOutbox;
import com.amazonaws.samples.repository.FlightRepository;
import com.amazonaws.samples.repository.OutboxRepository;
import com.amazonaws.samples.service.QueueService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import software.amazon.awssdk.services.sqs.SqsClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import software.amazon.awssdk.services.sqs.model.BatchResultErrorEntry;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlResponse;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchRequestEntry;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchResponse;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchResultEntry;
import software.amazon.awssdk.services.sqs.model.SqsException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Testcontainers
@SpringBootTest(properties = {
        "scheduling.enabled=false",
        "sqs.queue-name=flightQueue.fifo",
        "sqs.retry.delay-ms=1",
        "spring.datasource.url=",
        "spring.datasource.username=",
        "spring.datasource.password=",
})
class OutboxRelayTests {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @MockitoBean
    SqsClient sqsClient;

    @Autowired
    FlightController flightController;
    @Autowired
    QueueService queueService;
    @Autowired
    OutboxRepository outboxRepository;
    @Autowired
    FlightRepository flightRepository;
    @Autowired
    ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        outboxRepository.deleteAllInBatch();
        flightRepository.deleteAllInBatch();
        reset(sqsClient);
        when(sqsClient.getQueueUrl(any(GetQueueUrlRequest.class)))
                .thenReturn(GetQueueUrlResponse.builder().queueUrl("https://sqs.local/flightQueue.fifo").build());
    }

    private Flight bookFlight() {
        Flight flight = new Flight();
        flight.setDepartureAirport("CDG");
        flight.setArrivalAirport("JFK");
        flight.setDepartureDateTime(LocalDateTime.of(2026, 12, 1, 10, 0));
        flight.setArrivalDateTime(LocalDateTime.of(2026, 12, 1, 18, 0));
        return flightController.createFlight(flight);
    }

    private static SendMessageBatchResponse acceptAll(SendMessageBatchRequest request) {
        return SendMessageBatchResponse.builder()
                .successful(request.entries().stream()
                        .map(e -> SendMessageBatchResultEntry.builder().id(e.id()).messageId("m-" + e.id()).build())
                        .toList())
                .build();
    }

    @Test
    void bookingWritesFlightAndOutboxEventAtomically() {
        Flight saved = bookFlight();

        assertThat(flightRepository.findById(saved.getId())).isPresent();
        List<FlightOutbox> events = outboxRepository.findAll();
        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.getAggregateId()).isEqualTo(saved.getId().toString());
            assertThat(event.getEventType()).isEqualTo(FlightOutbox.EventType.FLIGHT_BOOKED);
            JsonNode payload = objectMapper.readTree(event.getPayload());
            assertThat(payload.get("id").asLong()).isEqualTo(saved.getId());
            assertThat(payload.get("departureAirport").asString()).isEqualTo("CDG");
            assertThat(payload.get("departureDateTime").asString()).isEqualTo("2026-12-01T10:00:00");
        });
    }

    @Test
    void relaysEventsInOrderAndDeletesThem() {
        Flight first = bookFlight();
        Flight second = bookFlight();
        when(sqsClient.sendMessageBatch(any(SendMessageBatchRequest.class)))
                .thenAnswer(inv -> acceptAll(inv.getArgument(0)));

        queueService.forwardEventsToSQS();

        ArgumentCaptor<SendMessageBatchRequest> captor = ArgumentCaptor.forClass(SendMessageBatchRequest.class);
        verify(sqsClient).sendMessageBatch(captor.capture());
        List<SendMessageBatchRequestEntry> entries = captor.getValue().entries();
        assertThat(entries).extracting(SendMessageBatchRequestEntry::messageGroupId)
                .containsExactly(first.getId().toString(), second.getId().toString());
        assertThat(entries).allSatisfy(e -> assertThat(e.messageDeduplicationId()).isEqualTo(e.id()));
        assertThat(outboxRepository.count()).isZero();
    }

    @Test
    void keepsEventsThatSqsRejectedInAPartialBatch() {
        bookFlight();
        bookFlight();
        List<FlightOutbox> pending = outboxRepository.findAll();
        String acceptedId = pending.get(0).getId().toString();
        String rejectedId = pending.get(1).getId().toString();
        when(sqsClient.sendMessageBatch(any(SendMessageBatchRequest.class))).thenReturn(SendMessageBatchResponse.builder()
                .successful(SendMessageBatchResultEntry.builder().id(acceptedId).messageId("m1").build())
                .failed(BatchResultErrorEntry.builder().id(rejectedId).code("InternalError").senderFault(false).build())
                .build());

        queueService.forwardEventsToSQS();

        assertThat(outboxRepository.findAll()).extracting(e -> e.getId().toString()).containsExactly(rejectedId);
    }

    @Test
    void keepsAllEventsWhenSqsIsUnavailable() {
        bookFlight();
        when(sqsClient.sendMessageBatch(any(SendMessageBatchRequest.class)))
                .thenThrow(SqsException.builder().message("unavailable").statusCode(503).build());

        assertThatThrownBy(() -> queueService.forwardEventsToSQS()).isInstanceOf(SqsException.class);

        verify(sqsClient, times(5)).sendMessageBatch(any(SendMessageBatchRequest.class));
        assertThat(outboxRepository.count()).isOne();
    }

    @Test
    void retriesInAFreshTransactionAndEventuallyRelays() {
        bookFlight();
        when(sqsClient.sendMessageBatch(any(SendMessageBatchRequest.class)))
                .thenThrow(SqsException.builder().message("throttled").statusCode(503).build())
                .thenAnswer(inv -> acceptAll(inv.getArgument(0)));

        queueService.forwardEventsToSQS();

        verify(sqsClient, times(2)).sendMessageBatch(any(SendMessageBatchRequest.class));
        assertThat(outboxRepository.count()).isZero();
    }

    @Test
    void concurrentRelaysNeverPublishTheSameEventTwice() throws Exception {
        for (int i = 0; i < 12; i++) {
            bookFlight();
        }
        List<String> published = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch firstSendStarted = new CountDownLatch(1);
        when(sqsClient.sendMessageBatch(any(SendMessageBatchRequest.class))).thenAnswer(inv -> {
            SendMessageBatchRequest request = inv.getArgument(0);
            firstSendStarted.countDown();
            Thread.sleep(200); // hold the row locks while the other relay tries to read
            request.entries().forEach(e -> published.add(e.messageDeduplicationId()));
            return acceptAll(request);
        });

        CompletableFuture<Void> relayA = CompletableFuture.runAsync(queueService::forwardEventsToSQS);
        assertThat(firstSendStarted.await(10, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<Void> relayB = CompletableFuture.runAsync(queueService::forwardEventsToSQS);
        CompletableFuture.allOf(relayA, relayB).get(30, TimeUnit.SECONDS);
        while (outboxRepository.count() > 0) {
            queueService.forwardEventsToSQS();
        }

        verify(sqsClient, atLeastOnce()).sendMessageBatch(any(SendMessageBatchRequest.class));
        assertThat(published).hasSize(12).doesNotHaveDuplicates();
    }
}
