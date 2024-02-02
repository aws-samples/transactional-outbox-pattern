package com.amazonaws.samples.controller;

import com.amazonaws.samples.model.Flight;
import io.awspring.cloud.dynamodb.DynamoDbTemplate;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import org.apache.commons.collections4.IterableUtils;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api")
@AllArgsConstructor
public class FlightController {

    private final DynamoDbTemplate dynamoDbTemplate;

    @GetMapping("/flights")
    public List<Flight> getAllFlights() {
        return IterableUtils.toList(dynamoDbTemplate.scanAll(Flight.class).items());
    }

    @PostMapping("/flights")
    @Transactional
    public Flight createFlight(@Valid @RequestBody Flight flight) {
        return dynamoDbTemplate.save(flight);
    }
}
