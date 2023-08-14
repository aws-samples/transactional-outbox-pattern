package com.amazonaws.samples.controller;

import com.amazonaws.samples.model.Flight;
import com.amazonaws.samples.model.FlightOutbox;
import com.amazonaws.samples.repository.FlightRepository;
import com.amazonaws.samples.repository.OutboxRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api")
@AllArgsConstructor
public class FlightController {
    final FlightRepository flightRepository;
    final OutboxRepository outboxRepository;
    final ObjectMapper objectMapper;
    @GetMapping("/flights")
    public List<Flight> getAllFlights() {
        return flightRepository.findAll();
    }
    @PostMapping("/flights")
    @Transactional
    public Flight createFlight(@Valid @RequestBody Flight flight) {
        Flight savedFlight = flightRepository.save(flight);
        JsonNode flightPayload = objectMapper.convertValue(flight, JsonNode.class);
        FlightOutbox outboxEvent = new FlightOutbox(flight.getId().toString(), FlightOutbox.EventType.FLIGHT_BOOKED, flightPayload);
        outboxRepository.save(outboxEvent);
        return savedFlight;
    }
}
