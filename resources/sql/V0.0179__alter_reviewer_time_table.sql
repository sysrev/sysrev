ALTER TABLE reviewer_event RENAME COLUMN id to reviewer_event_id;

CREATE INDEX reviewer_event_created_idx ON reviewer_event USING BRIN (created) with (autosummarize = 'on');
