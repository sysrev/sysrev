create index a_article_id_idx on article (article_id);

create index ac_id_idx on article_criteria (article_criteria_id);
create index ac_article_id_idx on article_criteria (article_id);
create index ac_criteria_id_idx on article_criteria (criteria_id);
create index ac_user_id_idx on article_criteria (user_id);
create index ac_answer_idx on article_criteria (answer);
create index ac_added_time_idx on article_criteria (added_time);
create index ac_updated_time_idx on article_criteria (updated_time);
create index ac_confirm_time_idx on article_criteria (confirm_time);

create index ar_article_id_idx on article_ranking (_1);
create index ar_score_idx on article_ranking (_2);

create index c_id_idx on criteria (criteria_id);

create index wu_id_idx on web_user (id);
create index wu_email_idx on web_user (email);
create index wu_verify_code_idx on web_user (verify_code);
