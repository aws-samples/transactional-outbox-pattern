package com.amazonaws.samples.service;

import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import java.util.List;

@Service
@Transactional
@Log4j2
public class PaymentService {
    private final SqsClient sqsClient;

    @Value("${sqs.queue_name}")
    private String sqsQueueName;

    public PaymentService() {
        this.sqsClient = SqsClient.builder()
                .build();
    }

    @Scheduled(fixedDelayString = "${sqs.polling_ms}")
    @Retryable(maxAttempts = 5, backoff = @Backoff(delay = 2000, multiplier = 2))
    public void readEventsFromSQS() {
        GetQueueUrlRequest getQueueRequest = GetQueueUrlRequest.builder()
                .queueName(sqsQueueName)
                .build();
        String sqsQueueUrl = sqsClient.getQueueUrl(getQueueRequest).queueUrl();
        ReceiveMessageRequest receiveRequest = ReceiveMessageRequest.builder()
                .queueUrl(sqsQueueUrl)
                .build();
        List<Message> messages = sqsClient.receiveMessage(receiveRequest).messages();

        // Print out the messages
        for (Message m : messages) {
            log.info("Flight event received: " + m.body() + ". Processing payment.");
            DeleteMessageRequest deleteMessageRequest = DeleteMessageRequest.builder()
                    .queueUrl(sqsQueueUrl)
                    .receiptHandle(m.receiptHandle())
                    .build();
            sqsClient.deleteMessage(deleteMessageRequest);
        }

    }
}