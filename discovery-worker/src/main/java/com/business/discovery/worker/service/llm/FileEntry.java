package com.business.discovery.worker.service.llm;

import com.business.discovery.worker.constants.FileType;

public record FileEntry(String path, FileType type, String description) {
}
