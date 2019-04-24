alter table label_similarity
      alter column predict_run_id set not null;
alter table label_similarity
      add constraint ls_predict_run_id_fkey
      foreign key (predict_run_id) references predict_run
      on delete cascade;

alter table label_predicts
      alter column predict_run_id set not null;
alter table label_predicts
      add constraint lr_predict_run_id_fkey
      foreign key (predict_run_id) references predict_run
      on delete cascade;

create index ls_predict_run_id_idx on label_similarity (predict_run_id);
create index lr_predict_run_id_idx on label_predicts (predict_run_id);
