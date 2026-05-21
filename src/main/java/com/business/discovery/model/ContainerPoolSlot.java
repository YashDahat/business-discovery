package com.business.discovery.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "container_pool_slot")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ContainerPoolSlot {

    // Single row — always id = 1
    @Id
    @Column(name = "id")
    @Builder.Default
    private Integer id = 1;

    // Configured via ENV — MAX_POOL_SIZE
    @Column(name = "pool_size", nullable = false)
    private Integer poolSize;

    // Currently active containers
    @Column(name = "active_slots", nullable = false)
    @Builder.Default
    private Integer activeSlots = 0;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    // ─── Convenience methods ──────────────────────────────

    public boolean hasAvailableSlot() {
        return activeSlots < poolSize;
    }

    public int availableSlots() {
        return poolSize - activeSlots;
    }

    public void acquire() {
        if (!hasAvailableSlot()) {
            throw new IllegalStateException(
                    "No available slots — pool is full (%d/%d)".formatted(activeSlots, poolSize)
            );
        }
        this.activeSlots++;
    }

    public void release() {
        if (activeSlots > 0) {
            this.activeSlots--;
        }
    }
}