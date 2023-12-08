package com.amazonaws.samples.service;

import com.amazonaws.AmazonServiceException;
import com.amazonaws.samples.AwsOutboxCDCSampleApplication;
import com.amazonaws.samples.model.Flight;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import java.io.IOException;
import java.util.function.Consumer;

@Service
public class QueueService {
    static final Logger logger = LoggerFactory.getLogger(AwsOutboxCDCSampleApplication.class);
    private final SqsClient sqsClient;
    private final ObjectMapper objectMapper;
    @Value("${sqs.queue_name}")
    private String sqsQueueName;

    public QueueService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.sqsClient = SqsClient.builder()
                .build();
    }

    @Bean
    public Consumer<Flight> sendToSQS() {
        return this::forwardEventsToSQS;
    }

    public void forwardEventsToSQS(Flight flight) {
        GetQueueUrlRequest getQueueRequest = GetQueueUrlRequest.builder()
                .queueName(sqsQueueName)
                .build();
        String queueUrl = this.sqsClient.getQueueUrl(getQueueRequest).queueUrl();
        try {
            SendMessageRequest send_msg_request = SendMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .messageBody(objectMapper.writeValueAsString(flight))
                    .messageGroupId("1")
                    .messageDeduplicationId(flight.getId().toString())
                    .build();
            sqsClient.sendMessage(send_msg_request);
        } catch (IOException | AmazonServiceException e) {
            logger.error("Error sending message to SQS", e);
        }
    }
}
