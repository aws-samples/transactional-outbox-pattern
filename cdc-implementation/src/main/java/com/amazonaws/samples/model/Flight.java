// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package com.amazonaws.samples.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.extensions.annotations.DynamoDbVersionAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;

import java.time.LocalDateTime;
import java.util.UUID;

@DynamoDbBean
@Getter
@Setter
@NoArgsConstructor
public class Flight {

    /**
     * Letters, digits, spaces and basic punctuation only. Besides keeping the data clean, this
     * guarantees the event payload only contains characters that Amazon SQS accepts, so a single
     * booking cannot become a permanently unpublishable event.
     */
    static final String AIRPORT_PATTERN = "^[\\p{L}\\p{M}\\p{N} .,'()/-]+$";
    static final String AIRPORT_MESSAGE = "must only contain letters, digits, spaces and . , ' ( ) / -";

    @Schema(accessMode = Schema.AccessMode.READ_ONLY)
    private UUID id;

    @NotBlank(message = "Departure airport cannot be blank")
    @Size(max = 64)
    @Pattern(regexp = AIRPORT_PATTERN, message = AIRPORT_MESSAGE)
    @Schema(example = "Paris")
    private String departureAirport;

    @NotBlank(message = "Destination airport cannot be blank")
    @Size(max = 64)
    @Pattern(regexp = AIRPORT_PATTERN, message = AIRPORT_MESSAGE)
    @Schema(example = "London")
    private String arrivalAirport;

    @NotNull
    @Schema(example = "2026-12-01T08:00:00")
    private LocalDateTime departureDateTime;

    @NotNull
    @Schema(example = "2026-12-01T09:15:00")
    private LocalDateTime arrivalDateTime;

    /**
     * Incremented by DynamoDB on every write (optimistic locking). Kinesis Data Streams for DynamoDB
     * may deliver changes out of order, so consumers must ignore an event whose version is lower
     * than one they already processed for the same flight.
     */
    @Schema(accessMode = Schema.AccessMode.READ_ONLY)
    private Long version;

    @DynamoDbPartitionKey
    public UUID getId() {
        return id;
    }

    @DynamoDbVersionAttribute
    public Long getVersion() {
        return version;
    }
}
