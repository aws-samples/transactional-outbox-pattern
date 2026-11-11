// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package com.amazonaws.samples.configuration;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** @param queueName name of the FIFO queue the CDC relay publishes to */
@Validated
@ConfigurationProperties(prefix = "sqs")
public record SqsProperties(@NotBlank String queueName) {
}
