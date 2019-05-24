alter table s3store rename column id to s3_id;

alter table annotation rename column id to annotation_id;
alter table annotation_web_user rename to ann_user;
alter table annotation_article rename to ann_article;
alter table annotation_semantic_class rename to ann_semantic_class;
alter table annotation_s3store rename to ann_s3store;
alter table ann_s3store rename column s3store_id to s3_id;
alter table semantic_class rename column id to semantic_class_id;

alter table compensation rename column id to compensation_id;
alter table compensation_project rename column active to enabled;
alter table compensation_user_period rename column web_user_id to user_id;

alter table web_user_profile_image rename to user_profile_image;
alter table user_profile_image rename column active to enabled;

alter table web_user_avatar_image rename to user_avatar_image;
alter table user_avatar_image rename column active to enabled;

alter table groups rename column id to group_id;
alter table web_user_group rename to user_group;
alter table user_group rename column active to enabled;

alter table web_user_stripe_acct rename to user_stripe;

alter table web_user_email rename to user_email;
alter table user_email rename column active to enabled;

alter table markdown rename column id to markdown_id;
