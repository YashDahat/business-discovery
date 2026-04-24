package com.business.discovery.agents;

import dev.langchain4j.service.spring.AiService;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import dev.langchain4j.agentic.Agent;

@AiService // Marks this for auto-wiring in Spring Boot
public interface Architect {

    @UserMessage("""
        You are a professional software engineer with more than 20 years of experience...
        Information: {{topic}}
        
        Using the Right-to-Left approach, design a maintainable architecture.
        """)
    @Agent(name = "softwareArchitect", description = "Designs system architecture based on business research")
    String softwareArchitect(@V("topic") String topic);
}