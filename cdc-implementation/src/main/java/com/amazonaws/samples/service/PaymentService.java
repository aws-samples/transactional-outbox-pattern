package com.amazonaws.samples.service;

import com.amazonaws.samples.AwsOutboxCDCSampleApplication;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
public class PaymentService {
    static final Logger logger = LoggerFactory.getLogger(AwsOutboxCDCSampleApplication.class);
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
            logger.info("Flight event received: " + m.body() + ". Processing payment.");
            DeleteMessageRequest deleteMessageRequest = DeleteMessageRequest.builder()
                    .queueUrl(sqsQueueUrl)
                    .receiptHandle(m.receiptHandle())
                    .build();
            sqsClient.deleteMessage(deleteMessageRequest);
        }

    }
}