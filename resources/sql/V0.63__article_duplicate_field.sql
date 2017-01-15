alter table article add column duplicate_of integer
      references article (article_id)
      on delete set null;
