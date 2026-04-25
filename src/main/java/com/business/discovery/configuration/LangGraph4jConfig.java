package com.business.discovery.configuration;

import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;
import org.bsc.langgraph4j.checkpoint.PostgresSaver;
import org.bsc.langgraph4j.serializer.std.ObjectStreamStateSerializer;
import org.bsc.langgraph4j.state.AgentState;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import lombok.extern.slf4j.Slf4j;

import java.sql.SQLException;

@Slf4j
@Configuration
public class LangGraph4jConfig {

    @Value("${langgraph4j.postgres.host}")
    private String host;

    @Value("${langgraph4j.postgres.port}")
    private int port;

    @Value("${langgraph4j.postgres.database}")
    private String database;

    @Value("${langgraph4j.postgres.username}")
    private String username;

    @Value("${langgraph4j.postgres.password}")
    private String password;

    @Bean
    public BaseCheckpointSaver postgresCheckpointSaver() throws SQLException {
        log.info("Initializing LangGraph4j PostgreSQL checkpoint saver — host: {}, db: {}",
                host, database);
        var stateSerializer = new ObjectStreamStateSerializer<>(AgentState::new);

        return PostgresSaver.builder()
                .host(host)
                .port(port)
                .database(database)
                .user(username)
                .password(password)
                .stateSerializer(stateSerializer)
                .createTables(true)   // auto-create checkpoint tables on startup
                .dropTablesFirst(false)
                .build();
    }
}