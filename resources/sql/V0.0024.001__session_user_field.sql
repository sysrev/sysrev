alter table session add column user_id integer references web_user (id) on delete cascade;
