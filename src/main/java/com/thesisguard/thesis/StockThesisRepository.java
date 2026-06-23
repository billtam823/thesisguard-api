package com.thesisguard.thesis;

import com.thesisguard.stock.Stock;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface StockThesisRepository extends JpaRepository<StockThesis, Long> {
    Optional<StockThesis> findByStock(Stock stock);
    Optional<StockThesis> findByStockId(Long stockId);
    List<StockThesis> findByGenerationStatus(String generationStatus);
}
