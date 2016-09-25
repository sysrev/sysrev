alter table article_criteria
      drop constraint article_criteria_article_id_fkey;
alter table article_criteria
      add constraint ac_article_id_fkey
      foreign key (article_id) references article
      on delete cascade;

alter table article_criteria
      drop constraint article_criteria_criteria_id_fkey;
alter table article_criteria
      add constraint ac_criteria_id_fkey
      foreign key (criteria_id) references criteria
      on delete cascade;
