// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package com.amazonaws.samples.relay;

import com.amazonaws.samples.model.Flight;
import com.amazonaws.samples.service.QueueUrlResolver;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.InvalidMessageContentsException;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;
import software.amazon.awssdk.services.sqs.model.SqsException;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FlightEventPublisherTests {

    private final SqsClient sqsClient = mock(SqsClient.class);
    private final QueueUrlResolver queueUrlResolver = mock(QueueUrlResolver.class);
    private final FlightEventPublisher publisher =
            new FlightEventPublisher(sqsClient, queueUrlResolver, JsonMapper.builder().build());

    private static FlightChangeEvent event() {
        Flight flight = new Flight();
        flight.setId(UUID.randomUUID());
        flight.setVersion(3L);
        return new FlightChangeEvent("evt-1", "MODIFY", flight);
    }

    @Test
    void publishesWithFlightGroupChangeIdDedupAndVersionInPayload() {
        when(queueUrlResolver.queueUrl()).thenReturn("https://sqs.local/q.fifo");
        var captor = org.mockito.ArgumentCaptor.forClass(SendMessageRequest.class);
        when(sqsClient.sendMessage(captor.capture())).thenReturn(SendMessageResponse.builder().build());
        FlightChangeEvent event = event();

        assertThat(publisher.publish(event)).isTrue();

        assertThat(captor.getValue().messageGroupId()).isEqualTo(event.flight().getId().toString());
        assertThat(captor.getValue().messageDeduplicationId()).isEqualTo("evt-1");
        assertThat(captor.getValue().messageBody()).contains("\"version\":3");
    }

    @Test
    void skipsMessagesThatSqsWillNeverAccept() {
        when(sqsClient.sendMessage(any(SendMessageRequest.class))).thenThrow(InvalidMessageContentsException.builder()
                .awsErrorDetails(AwsErrorDetails.builder().errorMessage("Invalid characters").build()).build());

        assertThat(publisher.publish(event())).isFalse();
    }

    @Test
    void propagatesTransientFailuresSoTheRecordIsRetried() {
        when(sqsClient.sendMessage(any(SendMessageRequest.class)))
                .thenThrow(SqsException.builder().message("unavailable").statusCode(503).build());

        assertThatThrownBy(() -> publisher.publish(event())).isInstanceOf(SqsException.class);
    }
}
