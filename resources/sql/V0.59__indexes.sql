create index a_enabled_idx on article (enabled);

create index l_id_local_idx on label (label_id_local);
create index l_project_id_idx on label (project_id);
create index l_project_ordering_idx on label (project_ordering);
create index l_value_type_idx on label (value_type);
create index l_name_idx on label (name);
create index l_required_idx on label (required);
create index l_category_idx on label (category);
create index l_enabled_idx on label (enabled);

create index albl_local_id_idx on article_label (article_label_local_id);
create index albl_article_id_idx on article_label (article_id);
create index albl_label_id_idx on article_label (label_id);
create index albl_user_id_idx on article_label (user_id);
create index albl_answer_idx on article_label (answer);
create index albl_added_time_idx on article_label (added_time);
create index albl_updated_time_idx on article_label (updated_time);
create index albl_confirm_time_idx on article_label (confirm_time);
create index albl_imported_idx on article_label (imported);

create index ls_label_id_idx on label_similarity (label_id);

create index lp_label_id_idx on label_predicts (label_id);
