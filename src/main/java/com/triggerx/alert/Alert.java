package com.triggerx.alert;

import com.triggerx.user.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "alerts")
@Getter
@Setter
@NoArgsConstructor
public class Alert {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    @Column(nullable = false, length = 20)
    private String symbol;

    @Column(name = "target_price", nullable = false, precision = 19, scale = 8)
    private BigDecimal targetPrice;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 7)
    private AlertCondition condition;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private AlertStatus status = AlertStatus.ACTIVE;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "triggered_at")
    private LocalDateTime triggeredAt;

    @Column(name = "triggered_price", precision = 19, scale = 8)
    private BigDecimal triggeredPrice;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    // Use compareTo() not equals() - BigDecimal.equals() checks scale: 80000.00 != 80000
    public boolean isTriggeredBy(BigDecimal currentPrice, BigDecimal prevPrice) {
        return switch (this.condition) {
            case ABOVE   -> currentPrice.compareTo(this.targetPrice) > 0;
            case BELOW   -> currentPrice.compareTo(this.targetPrice) < 0;
            case CROSSES -> {
                if (prevPrice == null) yield false;
                boolean crossedDown = prevPrice.compareTo(this.targetPrice) > 0
                                   && currentPrice.compareTo(this.targetPrice) <= 0;
                boolean crossedUp   = prevPrice.compareTo(this.targetPrice) < 0
                                   && currentPrice.compareTo(this.targetPrice) >= 0;
                yield crossedDown || crossedUp;
            }
        };
    }
}
