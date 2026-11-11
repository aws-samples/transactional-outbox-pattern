// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package com.amazonaws.samples.relay;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;

import java.util.function.Consumer;

/**
 * Relays DynamoDB change records from Kinesis to SQS.
 *
 * <p>Delivery guarantee: the binder checkpoints a Kinesis record only after this consumer returned
 * normally (checkpoint-mode=record). When SQS is unavailable the publish throws; once the binder's
 * retries are exhausted Spring Cloud Stream's default error handling rethrows the failure, so the
 * binder rewinds to the last checkpoint and retries the same record until SQS recovers. Nothing is
 * skipped and ordering is preserved (see CdcRelayIntegrationTests).
 *
 * <p>Do not register an {@code error-handler-definition} for this binding unless it rethrows:
 * a handler that swallows the error lets the binder checkpoint past a record that was never
 * delivered.
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
public class CdcRelayConfiguration {

    @Bean
    public Consumer<Message<byte[]>> sendToSQS(DynamoDbChangeEventParser parser, FlightEventPublisher publisher) {
        return message -> parser.parse(message.getPayload()).ifPresent(event -> {
            if (publisher.publish(event)) {
                log.info("Relayed {} change {} for flight {} (version {})", event.eventName(), event.eventId(),
                        event.flight().getId(), event.flight().getVersion());
            }
        });
    }
}
