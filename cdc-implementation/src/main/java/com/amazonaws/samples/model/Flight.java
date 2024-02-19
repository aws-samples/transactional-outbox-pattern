package com.amazonaws.samples.model;

import lombok.Getter;
import lombok.Setter;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;

import java.time.LocalDateTime;
import java.util.UUID;

@DynamoDbBean
@Getter
@Setter
public class Flight {

    private UUID id;
    private String departureAirport;
    private String arrivalAirport;
    private LocalDateTime departureDateTime;
    private LocalDateTime arrivalDateTime;
    public Flight() {
        this.id = UUID.randomUUID();
    }

    @DynamoDbPartitionKey
    public UUID getId() {
        return id;
    }

}
