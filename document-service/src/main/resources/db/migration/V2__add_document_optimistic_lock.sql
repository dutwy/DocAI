ALTER TABLE `document`
  ADD COLUMN `lock_version` INT NOT NULL DEFAULT 0;

UPDATE `document`
SET `status` = 'active'
WHERE `status` IS NULL OR TRIM(`status`) = '';

ALTER TABLE `document`
  ADD INDEX `idx_document_status_lock` (`status`, `lock_version`);
