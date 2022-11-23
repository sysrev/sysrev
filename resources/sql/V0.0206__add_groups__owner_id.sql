alter table groups add column owner_user_id integer references web_user (user_id);
create index groups_owner_user_id_idx on groups (owner_user_id);

with owners as (
  select user_id, group_id from user_group where '{owner}' <@ permissions
)
update groups set owner_user_id=owners.user_id from owners where groups.group_id = owners.group_id;

update user_group set permissions='{member,admin}' where '{owner}' <@ permissions;
