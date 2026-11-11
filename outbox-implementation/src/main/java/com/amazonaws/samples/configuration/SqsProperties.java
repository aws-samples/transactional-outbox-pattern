// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package com.amazonaws.samples.configuration;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * @param queueName name of the FIFO queue the relay publishes to
 * @param batchSize number of outbox rows relayed per poll (SQS batch limit is 10)
 */
@Validated
@ConfigurationProperties(prefix = "sqs")
public record SqsProperties(@NotBlank String queueName, @Min(1) @Max(10) int batchSize) {
}
