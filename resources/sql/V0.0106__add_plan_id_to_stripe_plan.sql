alter table stripe_plan add column id text;

comment on column stripe_plan.id is 'The plan id, as stored on Stripe Server';
