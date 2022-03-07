ALTER TABLE label ADD CONSTRAINT label_enabled_if_overall CHECK (enabled = TRUE OR name <> 'overall include') NOT VALID;
