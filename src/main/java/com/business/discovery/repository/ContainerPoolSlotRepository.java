package com.business.discovery.repository;

import com.business.discovery.model.ContainerPoolSlot;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ContainerPoolSlotRepository extends JpaRepository<ContainerPoolSlot, Integer> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM ContainerPoolSlot s WHERE s.id = :id")
    Optional<ContainerPoolSlot> findByIdForUpdate(@Param("id") Integer id);
}