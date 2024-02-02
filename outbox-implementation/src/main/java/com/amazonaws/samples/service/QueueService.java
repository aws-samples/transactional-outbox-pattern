package com.amazonaws.samples.service;

import com.amazonaws.samples.model.FlightOutbox;
import com.amazonaws.samples.repository.OutboxRepository;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Pageable;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchRequestEntry;

import java.util.ArrayList;
import java.util.List;

@Service
@Transactional
public class QueueService {
    private final OutboxRepository outboxRepository;
    private final SqsClient sqsClient;

    @Value("${sqs.queue_name}")
    private String sqsQueueName;

    @Value("${sqs.batch_size}")
    private int batchSize;

    public QueueService(OutboxRepository outboxRepository) {
        this.outboxRepository = outboxRepository;
        this.sqsClient = SqsClient.builder()
                .build();
    }

    @Scheduled(fixedDelayString = "${sqs.polling_ms}")
    @Retryable(maxAttempts = 5, backoff = @Backoff(delay = 2000, multiplier = 2))
    public void forwardEventsToSQS() {
        List<FlightOutbox> entities = outboxRepository.findAllByOrderByIdAsc(Pageable.ofSize(batchSize)).toList();
        if (!entities.isEmpty()) {
            GetQueueUrlRequest getQueueRequest = GetQueueUrlRequest.builder()
                    .queueName(sqsQueueName)
                    .build();
            String queueUrl = this.sqsClient.getQueueUrl(getQueueRequest).queueUrl();
            List<SendMessageBatchRequestEntry> messageEntries = new ArrayList<>();
            entities.forEach(entity -> messageEntries.add(SendMessageBatchRequestEntry.builder()
                    .id(entity.getId().toString())
                    .messageGroupId(entity.getAggregateId())
                    .messageDeduplicationId(entity.getId().toString())
                    .messageBody(entity.getPayload().toString())
                    .build())
            );
            SendMessageBatchRequest sendMessageBatchRequest = SendMessageBatchRequest.builder()
                    .queueUrl(queueUrl)
                    .entries(messageEntries)
                    .build();
            sqsClient.sendMessageBatch(sendMessageBatchRequest);
            outboxRepository.deleteAllInBatch(entities);
        }
    }
}
