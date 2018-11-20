-- A transaction should only ever be recorded once.
-- This prevents the condition where the same transaction could potentially
-- be added more than once to project_fund, artificially increasing the
-- funds available to a project
ALTER TABLE project_fund
      ADD CONSTRAINT pf_desc_transaction_id_unique
      UNIQUE (transaction_id);

-- Will help prevent internal errors where a single transaction
-- could potentially have multiple statuses
ALTER TABLE project_fund_pending
      ADD CONSTRAINT pfp_desc_transaction_id_unique
      UNIQUE (transaction_id);


