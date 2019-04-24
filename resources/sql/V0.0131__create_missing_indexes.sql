create index ann_article_id_idx on annotation (article_id);
create index ann_article_article_id_idx on annotation_article (article_id);
create index ann_article_annotation_id_idx on annotation_article (annotation_id);
create index ann_sc_annotation_id_idx
       on annotation_semantic_class (annotation_id);
create index ann_sc_semantic_class_id_idx
       on annotation_semantic_class (semantic_class_id);
create index ann_s3s_annotation_id_idx on annotation_s3store (annotation_id);
create index ann_s3s_s3store_id_idx on annotation_s3store (s3store_id);
create index ann_user_annotation_id_idx on annotation_web_user (annotation_id);
create index ann_user_user_id_idx on annotation_web_user (user_id);

create index comps_proj_compensation_id_idx on compensation_project (compensation_id);
create index comps_proj_project_id_idx on compensation_project (project_id);
create index comps_proj_default_compensation_id_idx
       on compensation_project_default (compensation_id);
create index comps_proj_default_project_id_idx
       on compensation_project_default (project_id);
create index comps_uperiod_compensation_id_idx
       on compensation_user_period (compensation_id);
create index comps_uperiod_user_id_idx
       on compensation_user_period (web_user_id);
create index comps_uperiod_period_begin_idx
       on compensation_user_period (period_begin);
create index comps_uperiod_period_end_idx
       on compensation_user_period (period_end);

create index anote_article_id_idx on article_note (article_id);
create index anote_user_id_idx on article_note (user_id);
create index anote_added_time_idx on article_note (added_time);
create index anote_updated_time_idx on article_note (updated_time);

create index article_pdf_article_id_idx on article_pdf (article_id);
create index article_pdf_s3_id_idx on article_pdf (s3_id);
