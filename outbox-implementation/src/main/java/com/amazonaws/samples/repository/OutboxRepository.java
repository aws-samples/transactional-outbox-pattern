// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package com.amazonaws.samples.repository;

import com.amazonaws.samples.model.FlightOutbox;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.util.List;

public interface OutboxRepository extends JpaRepository<FlightOutbox, Long> {

    /**
     * Oldest events first, locked (SELECT ... FOR UPDATE) so that concurrent relays in other
     * tasks wait instead of publishing the same rows.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<FlightOutbox> findAllByOrderByIdAsc(Limit limit);
}
