// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package com.amazonaws.samples.controller;

import com.amazonaws.samples.model.Flight;
import io.awspring.cloud.dynamodb.DynamoDbTableNameResolver;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class FlightController {

    private final DynamoDbTable<Flight> flights;

    public FlightController(DynamoDbEnhancedClient enhancedClient, DynamoDbTableNameResolver tableNameResolver) {
        this.flights = enhancedClient.table(tableNameResolver.resolve(Flight.class), TableSchema.fromBean(Flight.class));
    }

    @GetMapping("/flights")
    public List<Flight> getAllFlights() {
        return flights.scan().items().stream().toList();
    }

    /**
     * Only the DynamoDB write happens here; the change is captured by Kinesis Data Streams and
     * relayed to SQS asynchronously (see {@link com.amazonaws.samples.relay.CdcRelayConfiguration}).
     */
    @PostMapping("/flights")
    public Flight createFlight(@Valid @RequestBody Flight flight) {
        // Server-generated ID: a client-supplied ID would silently overwrite an existing item.
        flight.setId(UUID.randomUUID());
        flight.setVersion(null);
        flights.putItem(flight);
        // Return the stored item (with the version assigned on write); strongly consistent so a
        // just-written item is never missing.
        return flights.getItem(request -> request
                .key(Key.builder().partitionValue(flight.getId().toString()).build())
                .consistentRead(true));
    }
}
