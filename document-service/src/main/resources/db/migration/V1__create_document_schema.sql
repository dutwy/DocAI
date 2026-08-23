CREATE TABLE IF NOT EXISTS `document` (
  `id` VARCHAR(64) NOT NULL PRIMARY KEY,
  `title` VARCHAR(255) NOT NULL,
  `content` TEXT,
  `summary` TEXT,
  `keywords` TEXT,
  `file_id` VARCHAR(64),
  `user_id` BIGINT,
  `bucket_name` VARCHAR(128),
  `object_name` VARCHAR(512),
  `status` VARCHAR(20) NOT NULL DEFAULT 'active',
  `version` INT NOT NULL DEFAULT 1,
  `category` VARCHAR(50),
  `tags` TEXT,
  `created_by` VARCHAR(64),
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
  INDEX `idx_user_id` (`user_id`),
  INDEX `idx_bucket_name` (`bucket_name`),
  INDEX `idx_status` (`status`),
  INDEX `idx_category` (`category`)
);

CREATE TABLE IF NOT EXISTS `document_access` (
  `id` VARCHAR(64) NOT NULL PRIMARY KEY,
  `document_id` VARCHAR(64) NOT NULL,
  `bucket_name` VARCHAR(128) NOT NULL,
  `user_id` BIGINT NOT NULL,
  `role` VARCHAR(20) NOT NULL DEFAULT 'editor',
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT `uk_document_user` UNIQUE (`document_id`, `user_id`),
  INDEX `idx_user_bucket` (`user_id`, `bucket_name`),
  INDEX `idx_access_bucket_name` (`bucket_name`)
);

CREATE TABLE IF NOT EXISTS `document_version` (
  `id` VARCHAR(64) NOT NULL PRIMARY KEY,
  `document_id` VARCHAR(64) NOT NULL,
  `version_number` INT NOT NULL,
  `title` VARCHAR(255) NOT NULL,
  `content` TEXT,
  `summary` TEXT,
  `keywords` TEXT,
  `change_log` VARCHAR(500),
  `created_by` VARCHAR(64),
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT `uk_document_version` UNIQUE (`document_id`, `version_number`),
  INDEX `idx_version_document_id` (`document_id`),
  INDEX `idx_version_number` (`version_number`)
);

CREATE TABLE IF NOT EXISTS `document_comment` (
  `id` VARCHAR(64) NOT NULL PRIMARY KEY,
  `document_id` VARCHAR(64) NOT NULL,
  `user_id` BIGINT,
  `content` TEXT,
  `parent_id` VARCHAR(64),
  `created_by` VARCHAR(64),
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
  `status` VARCHAR(20) NOT NULL DEFAULT 'active',
  INDEX `idx_comment_document_id` (`document_id`),
  INDEX `idx_comment_user_id` (`user_id`),
  INDEX `idx_parent_id` (`parent_id`)
);

CREATE TABLE IF NOT EXISTS `document_annotation` (
  `id` VARCHAR(64) NOT NULL PRIMARY KEY,
  `document_id` VARCHAR(64) NOT NULL,
  `user_id` BIGINT,
  `line_number` INT,
  `start_offset` INT,
  `end_offset` INT,
  `annotation_type` VARCHAR(50),
  `content` TEXT,
  `color` VARCHAR(20),
  `status` VARCHAR(20) NOT NULL DEFAULT 'active',
  `created_by` VARCHAR(64),
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
  INDEX `idx_annotation_document_id` (`document_id`),
  INDEX `idx_annotation_user_id` (`user_id`),
  INDEX `idx_line_number` (`line_number`)
);
