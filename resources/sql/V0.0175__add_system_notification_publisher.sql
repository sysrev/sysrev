CREATE TYPE notification_unique_publisher_type AS ENUM ('system');

ALTER TABLE notification_publisher ADD COLUMN unique_publisher_type notification_unique_publisher_type UNIQUE;
