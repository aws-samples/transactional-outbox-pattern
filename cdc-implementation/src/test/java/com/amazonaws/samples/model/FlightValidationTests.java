// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package com.amazonaws.samples.model;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class FlightValidationTests {

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    private static Flight flight(String departure) {
        Flight flight = new Flight();
        flight.setDepartureAirport(departure);
        flight.setArrivalAirport("London");
        flight.setDepartureDateTime(LocalDateTime.of(2026, 12, 1, 8, 0));
        flight.setArrivalDateTime(LocalDateTime.of(2026, 12, 1, 9, 15));
        return flight;
    }

    @ParameterizedTest
    @ValueSource(strings = {"Paris", "CDG", "São Paulo (GRU)", "Saint-Denis / La Réunion", "Xi'an", "北京"})
    void acceptsRealAirportNames(String name) {
        assertThat(VALIDATOR.validate(flight(name))).isEmpty();
    }

    /** Characters SQS rejects (e.g. U+FFFF, control characters) must never reach the outbox/stream. */
    @ParameterizedTest
    @ValueSource(strings = {"Paris\uFFFF", "Par\u0000is", "Paris\u0007", "<script>", "   ", ""})
    void rejectsCharactersOutsideTheAllowedSet(String name) {
        assertThat(VALIDATOR.validate(flight(name))).isNotEmpty();
    }
}
