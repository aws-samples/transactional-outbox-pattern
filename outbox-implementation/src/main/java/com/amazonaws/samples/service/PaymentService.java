// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package com.amazonaws.samples.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

import java.util.List;

/** Simulated downstream consumer of the flight events. */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final SqsClient sqsClient;
    private final QueueUrlResolver queueUrlResolver;

    @Scheduled(fixedDelayString = "${sqs.polling-ms}")
    @Retryable(maxAttempts = 5, backoff = @Backoff(delay = 2000, multiplier = 2))
    public void readEventsFromSQS() {
        String queueUrl = queueUrlResolver.queueUrl();
        List<Message> messages = sqsClient.receiveMessage(ReceiveMessageRequest.builder()
                        .queueUrl(queueUrl)
                        .maxNumberOfMessages(10)
                        .messageSystemAttributeNames(MessageSystemAttributeName.MESSAGE_GROUP_ID)
                        .build())
                .messages();

        for (Message message : messages) {
            log.info("Flight event {} received for flight {}. Processing payment.", message.messageId(),
                    message.attributes().get(MessageSystemAttributeName.MESSAGE_GROUP_ID));
            sqsClient.deleteMessage(DeleteMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .receiptHandle(message.receiptHandle())
                    .build());
        }
    }
}
