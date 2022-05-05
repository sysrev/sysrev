alter table groups add constraint groups_name_valid
    check (created < '2022-05-06'
           OR (char_length(group_name) <= 40
               AND group_name ~ '^([A-Za-z0-9]+-)*[A-Za-z0-9]+$'));

alter table project add constraint project_name_valid
    check (date_created < '2022-05-06'
           OR (char_length(name) <= 40
               AND name ~ '^([A-Za-z0-9]+-)*[A-Za-z0-9]+$'));

alter table web_user add constraint web_user_username_valid
    check (char_length(username) <= 40
           AND username ~ '^([A-Za-z0-9]+-)*[A-Za-z0-9]+$');
