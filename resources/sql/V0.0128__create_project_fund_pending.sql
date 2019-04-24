CREATE TABLE project_fund_pending (
       id serial PRIMARY KEY,
       project_id integer,
       user_id integer,
       amount integer NOT NULL,
       transaction_id text NOT NULL,
       transaction_source text NOT NULL,
       status text NOT NULL,
       created integer NOT NULL,
       updated integer
);

COMMENT ON TABLE project_fund_pending is 'Similar structure to project_fund, except two additional columns: status and updated';
COMMENT ON COLUMN project_fund_pending.status is 'The status of the transactions, each transaction_source has its own statuses';
COMMENT ON COLUMN project_fund_pending.updated is 'The time that a transaction was completed, ideally project_fund_pending.updated = project_fund.created when project_fund_pending.status is that of a successfully completed transaction';
