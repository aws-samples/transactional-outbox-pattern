// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package com.amazonaws.samples.relay;

import com.amazonaws.samples.controller.FlightController;
import com.amazonaws.samples.model.Flight;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.services.kinesis.KinesisClient;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlResponse;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;
import software.amazon.awssdk.services.sqs.model.SqsException;

import java.net.URI;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static com.amazonaws.samples.relay.DynamoDbChangeEventParserTests.changeRecord;
import static com.amazonaws.samples.relay.DynamoDbChangeEventParserTests.flightImage;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Runs the real Kinesis binder against Kinesis and DynamoDB emulators and checks that an SQS
 * outage delays change records instead of losing them.
 */
@Testcontainers
@SpringBootTest(properties = {
        "scheduling.enabled=false",
        "spring.cloud.stream.bindings.sendToSQS-in-0.consumer.back-off-initial-interval=50",
        "spring.cloud.stream.bindings.sendToSQS-in-0.consumer.back-off-max-interval=100",
})
class CdcRelayIntegrationTests {

    static final String STREAM = "flightsCDCStream";
    static final String CHECKPOINT_TABLE = "test-checkpoints";
    static final String LOCK_TABLE = "test-locks";
    static final Region REGION = Region.US_EAST_1;
    static final StaticCredentialsProvider CREDENTIALS =
            StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"));

    @Container
    static final GenericContainer<?> KINESIS = new GenericContainer<>("ghcr.io/etspaceman/kinesis-mock:0.6.2")
            .withExposedPorts(4568)
            .withEnv("INITIALIZE_STREAMS", STREAM + ":1")
            .withEnv("AWS_REGION", REGION.id())
            .withEnv("CREATE_STREAM_DURATION", "0ms")
            .withEnv("LOG_LEVEL", "WARN")
            .waitingFor(Wait.forListeningPort());

    @Container
    static final GenericContainer<?> DYNAMODB = new GenericContainer<>("amazon/dynamodb-local:3.3.0")
            .withExposedPorts(8000)
            .withCommand("-jar DynamoDBLocal.jar -inMemory -sharedDb")
            .waitingFor(Wait.forListeningPort());

    @MockitoBean
    SqsClient sqsClient;

    @Autowired
    FlightController flightController;

    static URI kinesisEndpoint() {
        return URI.create("http://" + KINESIS.getHost() + ":" + KINESIS.getMappedPort(4568));
    }

    static URI dynamoDbEndpoint() {
        return URI.create("http://" + DYNAMODB.getHost() + ":" + DYNAMODB.getMappedPort(8000));
    }

    @DynamicPropertySource
    static void awsProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.aws.region.static", REGION::id);
        registry.add("spring.cloud.aws.credentials.access-key", () -> "test");
        registry.add("spring.cloud.aws.credentials.secret-key", () -> "test");
        registry.add("spring.cloud.aws.kinesis.endpoint", () -> kinesisEndpoint().toString());
        registry.add("spring.cloud.aws.dynamodb.endpoint", () -> dynamoDbEndpoint().toString());
        registry.add("KINESIS_STREAM_NAME", () -> STREAM);
        registry.add("BINDER_CHECKPOINT_TABLE", () -> CHECKPOINT_TABLE);
        registry.add("BINDER_LOCK_TABLE", () -> LOCK_TABLE);
        registry.add("SQS_QUEUE_NAME", () -> "flightQueue.fifo");
    }

    /** Same key schemas as the CDK stack; the application must not need to create them. */
    @BeforeAll
    static void createBinderTables() {
        try (DynamoDbClient dynamoDb = DynamoDbClient.builder().endpointOverride(dynamoDbEndpoint())
                .region(REGION).credentialsProvider(CREDENTIALS).build()) {
            for (var table : List.of(List.of(CHECKPOINT_TABLE, "metadataKey"), List.of(LOCK_TABLE, "lockKey"),
                    List.of("flight", "id"))) {
                dynamoDb.createTable(b -> b.tableName(table.get(0))
                        .billingMode(BillingMode.PAY_PER_REQUEST)
                        .keySchema(KeySchemaElement.builder().attributeName(table.get(1)).keyType(KeyType.HASH).build())
                        .attributeDefinitions(AttributeDefinition.builder().attributeName(table.get(1))
                                .attributeType(ScalarAttributeType.S).build()));
            }
        }
    }

    @Test
    void sqsOutageDelaysChangeRecordsButNeverLosesOrReordersThem() {
        when(sqsClient.getQueueUrl(any(GetQueueUrlRequest.class)))
                .thenReturn(GetQueueUrlResponse.builder().queueUrl("https://sqs.local/flightQueue.fifo").build());
        // SQS is down for more attempts than the binder's max-attempts, forcing a rewind.
        AtomicInteger calls = new AtomicInteger();
        when(sqsClient.sendMessage(any(SendMessageRequest.class))).thenAnswer(inv -> {
            if (calls.incrementAndGet() <= 7) {
                throw SqsException.builder().message("Service unavailable").statusCode(503).build();
            }
            return SendMessageResponse.builder().messageId("m-" + calls.get()).build();
        });

        try (KinesisClient kinesis = KinesisClient.builder().endpointOverride(kinesisEndpoint())
                .region(REGION).credentialsProvider(CREDENTIALS).build()) {
            put(kinesis, changeRecord("evt-insert", "INSERT", flightImage("CDG")));
            put(kinesis, changeRecord("evt-remove", "REMOVE", null));
            put(kinesis, "{ malformed".getBytes());
            put(kinesis, changeRecord("evt-modify", "MODIFY", flightImage("ORY", 2)));
        }

        ArgumentCaptor<SendMessageRequest> sent = ArgumentCaptor.forClass(SendMessageRequest.class);
        await().atMost(Duration.ofSeconds(90)).untilAsserted(() -> {
            verify(sqsClient, atLeast(9)).sendMessage(sent.capture());
            List<String> delivered = sent.getAllValues().subList(7, sent.getAllValues().size()).stream()
                    .map(SendMessageRequest::messageDeduplicationId).distinct().toList();
            assertThat(delivered).containsExactly("evt-insert", "evt-modify");
        });

        // Every failed attempt was for the first record: nothing was skipped while SQS was down.
        assertThat(sent.getAllValues().subList(0, 7)).extracting(SendMessageRequest::messageDeduplicationId)
                .containsOnly("evt-insert");
        assertThat(sent.getAllValues()).allSatisfy(request ->
                assertThat(request.messageGroupId()).isEqualTo(DynamoDbChangeEventParserTests.FLIGHT_ID.toString()));
    }

    @Test
    void bookingStoresAServerGeneratedIdAndVersionOne() {
        Flight request = new Flight();
        request.setId(DynamoDbChangeEventParserTests.FLIGHT_ID); // client-supplied ID must be ignored
        request.setVersion(42L);
        request.setDepartureAirport("Paris");
        request.setArrivalAirport("London");
        request.setDepartureDateTime(LocalDateTime.of(2026, 12, 1, 8, 0));
        request.setArrivalDateTime(LocalDateTime.of(2026, 12, 1, 9, 15));

        Flight saved = flightController.createFlight(request);

        assertThat(saved.getId()).isNotEqualTo(DynamoDbChangeEventParserTests.FLIGHT_ID);
        assertThat(saved.getVersion()).isEqualTo(1L);
        assertThat(flightController.getAllFlights()).filteredOn(f -> f.getId().equals(saved.getId()))
                .singleElement().satisfies(stored -> assertThat(stored.getVersion()).isEqualTo(1L));
    }

    private static void put(KinesisClient kinesis, byte[] data) {
        kinesis.putRecord(b -> b.streamName(STREAM).partitionKey("flight").data(SdkBytes.fromByteArray(data)));
    }
}
