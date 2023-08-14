package com.amazonaws.samples.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.sql.Timestamp;
import java.util.Date;


@Entity
@Table(name = "flights")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor(force = true)
public class Flight extends BaseEntity {

    @NotBlank(message = "Departure airport cannot be blank")
    private String departureAirport;

    @NotBlank(message = "Destination airport cannot be blank")
    private String arrivalAirport;

    @NotNull
    @Temporal(TemporalType.TIMESTAMP)
    private Date departureDateTime;

    @NotNull
    @Temporal(TemporalType.TIMESTAMP)
    private Date arrivalDateTime;

}
