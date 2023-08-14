package com.amazonaws.samples.model;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Entity
@Table(name = "flightsOutbox")
@EntityListeners(AuditingEntityListener.class)
@AllArgsConstructor
@NoArgsConstructor(force = true)
@Getter
@Setter
public class FlightOutbox extends BaseEntity {

    public enum EventType {
        FLIGHT_BOOKED,
        FLIGHT_CANCELED
    }

    private final String aggregateId;

    @Enumerated(EnumType.STRING)
    private final EventType eventType;

    @Column(columnDefinition = "json")
    @JdbcTypeCode(SqlTypes.JSON)
    private final JsonNode payload;
}
