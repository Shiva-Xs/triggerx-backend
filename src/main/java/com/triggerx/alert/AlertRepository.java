package com.triggerx.alert;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AlertRepository extends JpaRepository<Alert, UUID> {

    List<Alert> findByUserIdOrderByCreatedAtDesc(UUID userId);
    List<Alert> findByUserIdAndStatusOrderByCreatedAtDesc(UUID userId, AlertStatus status);
    Optional<Alert> findByIdAndUserId(UUID id, UUID userId);

    @Transactional(readOnly = true)
    @Query("SELECT a FROM Alert a JOIN FETCH a.user WHERE a.status = :status")
    List<Alert> findActiveWithUser(@Param("status") AlertStatus status);

    long countByUserIdAndStatus(UUID userId, AlertStatus status);
    long deleteByUserIdAndStatus(UUID userId, AlertStatus status);
    long deleteByUserIdAndSymbolAndStatus(UUID userId, String symbol, AlertStatus status);
    long countByStatus(AlertStatus status);
    long countBySymbolAndStatus(String symbol, AlertStatus status);

    @Query("""
        SELECT COUNT(a) > 0 FROM Alert a
        WHERE a.user.id     = :userId
          AND a.symbol      = :symbol
          AND a.condition   = :condition
          AND a.targetPrice = :targetPrice
          AND a.status      = :status
        """)
    boolean existsDuplicateActive(
            @Param("userId")      UUID userId,
            @Param("symbol")      String symbol,
            @Param("condition")   AlertCondition condition,
            @Param("targetPrice") BigDecimal targetPrice,
            @Param("status")      AlertStatus status
    );

    @Query("SELECT DISTINCT a.symbol FROM Alert a WHERE a.status = :status")
    List<String> findDistinctSymbolsByStatus(@Param("status") AlertStatus status);

    // AND status='ACTIVE': atomic race-condition guard - only one winner, duplicates skip
    @Transactional
    @Modifying
    @Query(value = """
        UPDATE alerts
        SET status = 'TRIGGERED',
            triggered_at = NOW(),
            triggered_price = :triggeredPrice
        WHERE id = :id
          AND status = 'ACTIVE'
        """, nativeQuery = true)
    int triggerAlert(@Param("id") UUID id,
                     @Param("triggeredPrice") BigDecimal triggeredPrice);
}
