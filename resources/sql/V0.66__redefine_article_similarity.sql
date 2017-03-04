drop table if exists article_similarity;

create table article_similarity(
    lo_id bigint,
    hi_id bigint,
    similarity double precision
);

create index article_similarity_similarity_idx on article_similarity using btree (similarity);
create index article_similarity_hi_id on article_similarity (hi_id);
create index article_similarity_lo_id on article_similarity (lo_id);
