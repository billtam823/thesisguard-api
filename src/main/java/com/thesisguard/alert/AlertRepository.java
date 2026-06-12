package com.thesisguard.alert;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AlertRepository extends JpaRepository<Alert, Long> {
    List<Alert> findAllByOrderByCreatedAtDesc();
    List<Alert> findByStockIdOrderByCreatedAtDesc(Long stockId);
}
