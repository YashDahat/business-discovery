package com.business.discovery.repository;

import com.business.discovery.model.GeneratedFile;
import com.business.discovery.model.GeneratedFile.FileStatus;
import com.business.discovery.model.GeneratedFile.FileType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface GeneratedFileRepository extends JpaRepository<GeneratedFile, UUID> {

    List<GeneratedFile> findByTaskId(UUID taskId);

    List<GeneratedFile> findByTaskIdAndStatus(UUID taskId, FileStatus status);

    List<GeneratedFile> findByTaskIdAndFileType(UUID taskId, FileType fileType);

    long countByTaskIdAndStatus(UUID taskId, FileStatus status);
}