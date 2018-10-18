ALTER TABLE project_payments
RENAME TO project_fund;

ALTER TABLE project_fund DROP COLUMN charge_id;
ALTER TABLE project_fund ADD COLUMN transaction_id text NOT NULL;
ALTER TABLE project_fund ADD COLUMN transaction_source text NOT NULL;

COMMENT ON COLUMN project_fund.transaction_id is 'The id of the object represent the transaction';
COMMENT ON COLUMN project_fund.transaction_source is 'The source of the funds, i.e. Stripe/charge-id or PayPal/payment-id';
