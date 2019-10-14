--- plan_user
ALTER TABLE plan_user
      ADD COLUMN created_time TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now();
UPDATE plan_user SET created_time = to_timestamp(created);
ALTER TABLE plan_user DROP COLUMN created;
ALTER TABLE plan_user RENAME COLUMN created_time TO created;

--- plan_group
ALTER TABLE plan_group
      ADD COLUMN created_time TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now();
UPDATE plan_group SET created_time = to_timestamp(created);
ALTER TABLE plan_group DROP COLUMN created;
ALTER TABLE plan_group RENAME COLUMN created_time TO created;

--- project_fund
ALTER TABLE project_fund
      ADD COLUMN created_time TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now();
UPDATE project_fund SET created_time = to_timestamp(created);
ALTER TABLE project_fund DROP COLUMN created;
ALTER TABLE project_fund RENAME COLUMN created_time TO created;

--- project_fund_pending
ALTER TABLE project_fund_pending
      ADD COLUMN created_time TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now();
UPDATE project_fund_pending SET created_time = to_timestamp(created);
ALTER TABLE project_fund_pending DROP COLUMN created;
ALTER TABLE project_fund_pending RENAME COLUMN created_time TO created;
--- project_fund_pending.updated
ALTER TABLE project_fund_pending
      ADD COLUMN updated_time TIMESTAMP WITH TIME ZONE;
UPDATE project_fund_pending SET updated_time = to_timestamp(updated)
       WHERE updated is not null;
ALTER TABLE project_fund_pending DROP COLUMN updated;
ALTER TABLE project_fund_pending RENAME COLUMN updated_time TO updated;

--- project_support_subscriptions
ALTER TABLE project_support_subscriptions
      ADD COLUMN created_time TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now();
UPDATE project_support_subscriptions SET created_time = to_timestamp(created);
ALTER TABLE project_support_subscriptions DROP COLUMN created;
ALTER TABLE project_support_subscriptions RENAME COLUMN created_time TO created;

--- stripe_plan
ALTER TABLE stripe_plan
      ADD COLUMN created_time TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now();
UPDATE stripe_plan SET created_time = to_timestamp(created);
ALTER TABLE stripe_plan DROP COLUMN created;
ALTER TABLE stripe_plan RENAME COLUMN created_time TO created;
