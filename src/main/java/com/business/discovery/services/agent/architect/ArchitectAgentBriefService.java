package com.business.discovery.services.agent.architect;

import com.business.discovery.model.ArchitectBrief;
import com.business.discovery.repository.ArchitectBriefRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ArchitectAgentBriefService {
    private final ArchitectBriefRepository architectBriefRepository;

    public Optional<ArchitectBrief> getBrief(UUID runId) {
        return architectBriefRepository.findByRunId(runId);
    }

    public List<ArchitectBrief> getBriefsByRunId(UUID runId) {
        return architectBriefRepository.findAllByRunId(runId);
    }
}
