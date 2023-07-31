-- Improve insert performance by removing non-essential FKs and indexes
-- We want writes to be as fast as possible since they happen on every request
-- Reads are very rare, and they can just do a scan as long as we
-- periodically trim old rows.

ALTER TABLE web_event
DROP CONSTRAINT web_event_project_id_fkey;

ALTER TABLE web_event
DROP CONSTRAINT web_event_skey_fkey;

ALTER TABLE web_event
DROP CONSTRAINT web_event_user_id_fkey;

DROP INDEX wev_event_type_idx;
DROP INDEX wev_is_error_idx;
DROP INDEX wev_project_id_idx;
DROP INDEX wev_user_id_idx;
