package com.thesisguard.review;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ThesisMonitorMemoryRepository extends JpaRepository<ThesisMonitorMemory, Long> {
    Optional<ThesisMonitorMemory> findByStockId(Long stockId);
}
