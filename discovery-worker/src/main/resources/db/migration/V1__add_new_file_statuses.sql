-- Run this manually if Hibernate ddl-auto=update does not automatically
-- update the generated_file_status_check constraint after adding SPEC_COMPLIANT
-- and GENERATION_FAILED to the GeneratedFile.FileStatus enum.
--
-- Execute against the business_discovery database before restarting the master.

ALTER TABLE generated_file DROP CONSTRAINT IF EXISTS generated_file_status_check;

ALTER TABLE generated_file ADD CONSTRAINT generated_file_status_check
    CHECK (status IN ('PENDING', 'GENERATED', 'VALIDATED', 'SPEC_COMPLIANT', 'GENERATION_FAILED', 'FAILED'));
