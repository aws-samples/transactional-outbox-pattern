// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package com.amazonaws.samples.configuration;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.sqs.SqsClient;

@Configuration(proxyBeanMethods = false)
public class AwsConfiguration {

    /** Single shared client; region and credentials come from the default provider chains. */
    @Bean(destroyMethod = "close")
    public SqsClient sqsClient() {
        return SqsClient.create();
    }

    /** Relative server URL so "Try it out" works behind CloudFront. */
    @Bean
    public OpenAPI openApi() {
        return new OpenAPI().addServersItem(new Server().url("/"));
    }
}
