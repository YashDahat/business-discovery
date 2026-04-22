// com/business/discovery/repository/ChatMemoryRepository.java
package com.business.discovery.repository;

import com.business.discovery.model.ChatMemoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatMemoryRepository extends JpaRepository<ChatMemoryEntity, Long> {}