ALTER TABLE stripe_plan DROP COLUMN amount;
ALTER TABLE stripe_plan DROP COLUMN product;
ALTER TABLE plan_user RENAME COLUMN product to plan;
ALTER TABLE plan_group RENAME COLUMN product to plan;


