create table stripe_plan (
  amount integer not null,
  product text not null primary key,
  name text not null);

comment on column stripe_plan.amount is 'Cost in USD cents';
comment on column stripe_plan.product is 'The product whose pricing this plan determines. Used as a unique identifier';
comment on column stripe_plan.name is 'name of the plan';

create table plan_user (
  product text not null references stripe_plan (product),
  user_id integer references web_user (user_id) on delete cascade not null);

comment on column plan_user.product is 'product id in stripe_plan';
comment on column plan_user.user_id is 'corresponds to a user in web_user';

