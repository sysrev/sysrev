alter table article_label add constraint al_unique_user_answer unique (user_id, article_id, label_id);
