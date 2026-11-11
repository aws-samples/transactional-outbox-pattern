// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package com.amazonaws.samples.relay;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DynamoDbChangeEventParserTests {

    static final UUID FLIGHT_ID = UUID.fromString("6f1c3f4e-2b0a-4c2e-9a53-6f1f0c8e9d11");

    private final DynamoDbChangeEventParser parser = new DynamoDbChangeEventParser(JsonMapper.builder().build());

    static byte[] changeRecord(String eventId, String eventName, String newImage) {
        String dynamodb = newImage == null
                ? "{\"Keys\":{\"id\":{\"S\":\"%s\"}}}".formatted(FLIGHT_ID)
                : "{\"Keys\":{\"id\":{\"S\":\"%s\"}},\"NewImage\":%s}".formatted(FLIGHT_ID, newImage);
        return """
                {"awsRegion":"eu-west-1","eventID":"%s","eventName":"%s","userIdentity":null,
                 "recordFormat":"application/json","tableName":"flight","dynamodb":%s,"eventSource":"aws:dynamodb"}
                """.formatted(eventId, eventName, dynamodb).getBytes(StandardCharsets.UTF_8);
    }

    static String flightImage(String departure) {
        return flightImage(departure, 1);
    }

    static String flightImage(String departure, long version) {
        return """
                {"id":{"S":"%s"},"departureAirport":{"S":"%s"},"arrivalAirport":{"S":"JFK"},
                 "departureDateTime":{"S":"2026-12-01T10:00"},"arrivalDateTime":{"S":"2026-12-01T18:00:30"},
                 "version":{"N":"%d"}}
                """.formatted(FLIGHT_ID, departure, version);
    }

    @Test
    void parsesInsertWithTheSourceFlightIdAndChangeId() {
        var event = parser.parse(changeRecord("evt-1", "INSERT", flightImage("CDG"))).orElseThrow();

        assertThat(event.eventId()).isEqualTo("evt-1");
        assertThat(event.eventName()).isEqualTo("INSERT");
        assertThat(event.flight().getId()).isEqualTo(FLIGHT_ID);
        assertThat(event.flight().getDepartureAirport()).isEqualTo("CDG");
        assertThat(event.flight().getDepartureDateTime()).isEqualTo(LocalDateTime.of(2026, 12, 1, 10, 0));
        assertThat(event.flight().getArrivalDateTime()).isEqualTo(LocalDateTime.of(2026, 12, 1, 18, 0, 30));
        assertThat(event.flight().getVersion()).isEqualTo(1L);
    }

    @Test
    void reparsingTheSameRecordIsStable() {
        byte[] record = changeRecord("evt-1", "INSERT", flightImage("CDG"));
        var first = parser.parse(record).orElseThrow();
        var second = parser.parse(record).orElseThrow();

        assertThat(second.eventId()).isEqualTo(first.eventId());
        assertThat(second.flight().getId()).isEqualTo(first.flight().getId());
    }

    @Test
    void parsesModify() {
        assertThat(parser.parse(changeRecord("evt-2", "MODIFY", flightImage("ORY", 2))))
                .hasValueSatisfying(e -> {
                    assertThat(e.flight().getDepartureAirport()).isEqualTo("ORY");
                    assertThat(e.flight().getVersion()).isEqualTo(2L);
                });
    }

    @Test
    void skipsRemove() {
        assertThat(parser.parse(changeRecord("evt-3", "REMOVE", null))).isEmpty();
    }

    @Test
    void skipsMalformedRecords() {
        assertThat(parser.parse("not json".getBytes(StandardCharsets.UTF_8))).isEmpty();
        assertThat(parser.parse("{}".getBytes(StandardCharsets.UTF_8))).isEmpty();
        assertThat(parser.parse(changeRecord("evt-4", "INSERT", null))).isEmpty();
        assertThat(parser.parse(changeRecord("evt-5", "INSERT", "{\"id\":{\"S\":\"not-a-uuid\"}}"))).isEmpty();
        assertThat(parser.parse(changeRecord("evt-6", "INSERT",
                flightImage("CDG").replace("2026-12-01T10:00", "tomorrow")))).isEmpty();
        assertThat(parser.parse(changeRecord("evt-7", "INSERT",
                flightImage("CDG").replace("\"version\"", "\"revision\"")))).isEmpty();
    }
}
