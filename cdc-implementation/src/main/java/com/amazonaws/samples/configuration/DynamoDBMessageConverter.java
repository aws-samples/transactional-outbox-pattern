package com.amazonaws.samples.configuration;

import com.amazonaws.samples.AwsOutboxCDCSampleApplication;
import com.amazonaws.samples.model.Flight;
import com.fasterxml.jackson.core.TreeNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.messaging.Message;
import org.springframework.messaging.converter.AbstractMessageConverter;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeType;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Component
public class DynamoDBMessageConverter extends AbstractMessageConverter {

    static final Logger logger = LoggerFactory.getLogger(AwsOutboxCDCSampleApplication.class);
    private final ObjectMapper objectMapper;

    public DynamoDBMessageConverter(ObjectMapper objectMapper) {
        super(new MimeType("application", "ddb"));
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean supports(Class<?> clazz) {
        return clazz.equals(Flight.class);
    }

    @Override
    protected Object convertFromInternal(Message<?> message, @NotNull Class<?> targetClass, @Nullable Object conversionHint) {
        try {
            TreeNode flightJson = objectMapper.readTree((byte[]) message.getPayload()).get("dynamodb").get("NewImage");
            String departureAirport = flightJson.get("departureAirport").get("S").toString();
            String arrivalAirport = flightJson.get("arrivalAirport").get("S").toString();
            LocalDateTime departureDateTime = LocalDateTime.parse(flightJson.get("departureDateTime").get("S").toString().replaceAll("\"", ""), DateTimeFormatter.ISO_DATE_TIME);
            LocalDateTime arrivalDateTime = LocalDateTime.parse(flightJson.get("arrivalDateTime").get("S").toString().replaceAll("\"", ""), DateTimeFormatter.ISO_DATE_TIME);
            Flight flight = new Flight();
            flight.setDepartureAirport(departureAirport);
            flight.setArrivalAirport(arrivalAirport);
            flight.setDepartureDateTime(departureDateTime);
            flight.setArrivalDateTime(arrivalDateTime);
            return flight;
        } catch (IOException e) {
            logger.error("Error converting DynamoDB stream message", e);
            return null;
        }
    }
}

