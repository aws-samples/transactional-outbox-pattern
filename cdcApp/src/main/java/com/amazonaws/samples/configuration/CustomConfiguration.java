package com.amazonaws.samples.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.converter.MessageConverter;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableRetry
@EnableScheduling
@AllArgsConstructor
public class CustomConfiguration {

    private final ObjectMapper objectMapper;

    @Bean
    public MessageConverter customMessageConverter() {
        return new DynamoDBMessageConverter(this.objectMapper);
    }

}
