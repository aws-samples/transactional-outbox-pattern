package com.amazonaws.samples;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class AwsOutboxCDCSampleApplication {

    static final Logger logger = LoggerFactory.getLogger(AwsOutboxCDCSampleApplication.class);

    public static void main(String[] args) {
        //Resolve dependency conflict
        System.setProperty("software.amazon.awssdk.http.service.impl","software.amazon.awssdk.http.urlconnection.UrlConnectionSdkHttpService");

        SpringApplication.run(AwsOutboxCDCSampleApplication.class, args);
    }

}
