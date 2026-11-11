// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package com.amazonaws.samples.service;

import com.amazonaws.samples.configuration.SqsProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;

/** Resolves the queue URL once instead of calling GetQueueUrl on every poll. */
@Component
@RequiredArgsConstructor
public class QueueUrlResolver {

    private final SqsClient sqsClient;
    private final SqsProperties sqsProperties;
    private volatile String queueUrl;

    public String queueUrl() {
        String url = queueUrl;
        if (url == null) {
            url = sqsClient.getQueueUrl(GetQueueUrlRequest.builder().queueName(sqsProperties.queueName()).build()).queueUrl();
            queueUrl = url;
        }
        return url;
    }
}
