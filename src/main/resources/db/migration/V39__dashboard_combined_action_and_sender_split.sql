ALTER TABLE cleanup_jobs DROP CONSTRAINT cleanup_jobs_action_type_check;
ALTER TABLE cleanup_jobs ADD CHECK (action_type IN ('MARK_READ','ARCHIVE','TRASH','MARK_READ_ARCHIVE'));

ALTER TABLE sender_group_corrections DROP CONSTRAINT sender_group_corrections_kind_check;
ALTER TABLE sender_group_corrections
    ADD COLUMN split_count integer,
    ADD COLUMN remainder_label varchar(160),
    ADD CHECK (kind IN ('MERGE','SEPARATE','SPLIT')),
    ADD CHECK (
        (kind <> 'SPLIT' AND split_count IS NULL AND remainder_label IS NULL)
        OR (kind = 'SPLIT' AND split_count IS NOT NULL AND split_count > 0
            AND split_count < cardinality(unit_ids) AND remainder_label IS NOT NULL AND label IS NOT NULL)
    );
