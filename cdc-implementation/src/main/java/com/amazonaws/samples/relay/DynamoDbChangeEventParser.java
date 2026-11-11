// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package com.amazonaws.samples.relay;

import com.amazonaws.samples.model.Flight;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.UUID;

/**
 * Parses the JSON records that DynamoDB writes to Kinesis Data Streams.
 *
 * <p>Returns {@link Optional#empty()} for records that must not be relayed (deletions, records
 * from another table, malformed payloads). Those are logged and skipped on purpose: retrying a
 * record that can never be parsed would block the shard forever.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DynamoDbChangeEventParser {

    private final ObjectMapper objectMapper;

    public Optional<FlightChangeEvent> parse(byte[] payload) {
        try {
            JsonNode root = objectMapper.readTree(payload);
            String eventId = requiredText(root, "eventID");
            String eventName = requiredText(root, "eventName");
            return switch (eventName) {
                case "INSERT", "MODIFY" -> Optional.of(new FlightChangeEvent(eventId, eventName,
                        toFlight(root.path("dynamodb").path("NewImage"))));
                case "REMOVE" -> {
                    log.info("Skipping REMOVE change record {}", eventId);
                    yield Optional.empty();
                }
                default -> {
                    log.warn("Skipping change record {} with unknown event name {}", eventId, eventName);
                    yield Optional.empty();
                }
            };
        }
        catch (JacksonException | IllegalArgumentException | DateTimeParseException ex) {
            log.error("Skipping malformed change record: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    private static Flight toFlight(JsonNode image) {
        if (image.isMissingNode()) {
            throw new IllegalArgumentException("change record has no NewImage");
        }
        Flight flight = new Flight();
        flight.setId(UUID.fromString(stringAttribute(image, "id")));
        flight.setDepartureAirport(stringAttribute(image, "departureAirport"));
        flight.setArrivalAirport(stringAttribute(image, "arrivalAirport"));
        flight.setDepartureDateTime(LocalDateTime.parse(stringAttribute(image, "departureDateTime")));
        flight.setArrivalDateTime(LocalDateTime.parse(stringAttribute(image, "arrivalDateTime")));
        flight.setVersion(Long.valueOf(numberAttribute(image, "version")));
        return flight;
    }

    private static String stringAttribute(JsonNode image, String name) {
        JsonNode value = image.path(name).path("S");
        if (!value.isString()) {
            throw new IllegalArgumentException("attribute " + name + " is missing or not a string");
        }
        return value.asString();
    }

    private static String numberAttribute(JsonNode image, String name) {
        JsonNode value = image.path(name).path("N");
        if (!value.isString()) {
            throw new IllegalArgumentException("attribute " + name + " is missing or not a number");
        }
        return value.asString();
    }

    private static String requiredText(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isString() || value.asString().isBlank()) {
            throw new IllegalArgumentException("field " + field + " is missing");
        }
        return value.asString();
    }
}
