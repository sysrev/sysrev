alter table stripe_plan add column created integer not null;

comment on column stripe_plan.created is 'UNIX epoch seconds, as stored on Stripe Server';

alter table plan_user add column created integer not null;
alter table plan_user add column sub_id text not null;

comment on column plan_user.created is 'UNIX epoch seconds when user was added to plan';
comment on column plan_user.sub_id is 'Stripe Subscription ID';
