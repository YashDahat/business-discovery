// com/business/discovery/entity/ChatMemoryEntity.java
package com.business.discovery.model;


import jakarta.persistence.*;
import lombok.Getter;

@Entity
@Table(name = "chat_memory")
public class ChatMemoryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long memoryId;

    @Column(columnDefinition = "TEXT")
    private String messagesJson;

    public ChatMemoryEntity() {}

    public ChatMemoryEntity(String messagesJson) {
        this.messagesJson = messagesJson;
    }

    public Long getMemoryId() { return memoryId; }
    public String getMessagesJson() { return messagesJson; }
    public void setMessagesJson(String messagesJson) { this.messagesJson = messagesJson; }
}