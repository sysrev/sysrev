drop trigger a_text_search_update on article;

alter table article drop column text_search;
alter table article drop column raw;
alter table article drop column primary_title;
alter table article drop column secondary_title;
alter table article drop column authors;
alter table article drop column abstract;
alter table article drop column urls;
alter table article drop column document_ids;
alter table article drop column keywords;
alter table article drop column nct_arm_name;
alter table article drop column notes;
alter table article drop column nct_arm_desc;
alter table article drop column remote_database_name;
alter table article drop column work_type;
alter table article drop column date;
alter table article drop column year;
alter table article drop column public_id;
