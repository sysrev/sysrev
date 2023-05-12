ALTER TABLE srvc_label_answer
  DROP CONSTRAINT srvc_label_answer_document_fkey;

ALTER TABLE srvc_label_answer
  RENAME COLUMN document TO event;

ALTER INDEX srvc_label_answer_document_idx
  RENAME TO srvc_label_answer_event_idx;
