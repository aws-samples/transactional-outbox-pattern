// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package com.amazonaws.samples.controller;

import com.amazonaws.samples.model.Flight;
import com.amazonaws.samples.model.FlightOutbox;
import com.amazonaws.samples.repository.FlightRepository;
import com.amazonaws.samples.repository.OutboxRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class FlightController {

    private final FlightRepository flightRepository;
    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    @GetMapping("/flights")
    public List<Flight> getAllFlights() {
        return flightRepository.findAll();
    }

    /** Persists the flight and its outbox event in the same database transaction. */
    @PostMapping("/flights")
    @Transactional
    public Flight createFlight(@Valid @RequestBody Flight flight) {
        flight.setId(null);
        Flight savedFlight = flightRepository.save(flight);
        String payload = objectMapper.writeValueAsString(savedFlight);
        outboxRepository.save(new FlightOutbox(savedFlight.getId().toString(), FlightOutbox.EventType.FLIGHT_BOOKED, payload));
        return savedFlight;
    }
}
