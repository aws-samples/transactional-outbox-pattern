package com.amazonaws.samples.repository;

import com.amazonaws.samples.model.FlightOutbox;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.stereotype.Repository;

@Repository
public interface OutboxRepository extends JpaRepository<FlightOutbox, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Page<FlightOutbox> findAllByOrderByIdAsc(Pageable pageable);
}