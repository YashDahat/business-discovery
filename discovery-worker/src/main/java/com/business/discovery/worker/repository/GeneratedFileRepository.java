package com.business.discovery.worker.repository;

import com.business.discovery.worker.model.GeneratedFile;
import com.business.discovery.worker.model.GeneratedFile.FileStatus;
import com.business.discovery.worker.model.GeneratedFile.FileType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface GeneratedFileRepository extends JpaRepository<GeneratedFile, UUID> {

    List<GeneratedFile> findByTaskId(UUID taskId);

    List<GeneratedFile> findByTaskIdAndStatus(UUID taskId, FileStatus status);

    List<GeneratedFile> findByTaskIdAndFileType(UUID taskId, FileType fileType);

    long countByTaskIdAndStatus(UUID taskId, FileStatus status);

    // Uses LIMIT 1 to tolerate any pre-existing duplicate rows from old runs.
    // The unique constraint on (task_id, file_path) prevents new duplicates.
    @Query(value = "SELECT * FROM generated_file WHERE task_id = :taskId AND file_path = :filePath ORDER BY attempt_number DESC LIMIT 1",
            nativeQuery = true)
    Optional<GeneratedFile> findByTaskIdAndFilePath(@Param("taskId") UUID taskId, @Param("filePath") String filePath);
}
