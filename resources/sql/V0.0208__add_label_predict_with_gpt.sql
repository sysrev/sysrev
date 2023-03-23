alter table label add column predict_with_gpt boolean not null default false;
create index label_predict_with_gpt_idx on label (predict_with_gpt) WHERE predict_with_gpt = true;
