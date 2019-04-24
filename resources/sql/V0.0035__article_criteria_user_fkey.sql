alter table article_criteria
      add constraint ac_user_id_fkey
      foreign key (user_id) references web_user (id)
      on delete cascade;
