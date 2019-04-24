create table project_support_subscriptions (
  id text not null primary key,
  project_id integer not null,
  user_id integer not null,
  stripe_id text not null,
  quantity integer not null,
  status text not null,
  created integer not null);

comment on column project_support_subscriptions.id is 'The Stripe subscription id';
comment on column project_support_subscriptions.project_id is 'The id associated with the project';
comment on column project_support_subscriptions.user_id is 'The SysRev user-id associated with this subscription';
comment on column project_support_subscriptions.stripe_id is 'The Stripe customer id associated with the SysRev user';
comment on column project_support_subscriptions.quantity is 'The amount of subscriptions to the 1 cent project support plan, equivalent to the amount of support for this project';
comment on column project_support_subscriptions.status is 'The status of this subscription';
comment on column project_support_subscriptions.created is 'The Unix time stamp for when this subscription was created';
