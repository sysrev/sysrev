alter table article_criteria drop constraint article_criteria_article_id_criteria_id_key;
alter table article_criteria add column user_id integer references site_user (id);
alter table article_criteria add constraint one_article_id_and_criteria_id_per_user_id unique (user_id, article_id, criteria_id);

