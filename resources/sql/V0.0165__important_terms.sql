CREATE TABLE important_terms (
    term_id serial primary key,
    term varchar(200),
    UNIQUE(term)
);


CREATE TABLE project_important_terms (
    project_id integer REFERENCES project(project_id) ON DELETE CASCADE NOT NULL,
    created timestamp WITH TIME ZONE NOT NULL DEFAULT now(),
    term_id integer REFERENCES important_terms(term_id),
    tfidf float4,
    unique (project_id, term_id)
    );

create index p_important_terms_term_idx on project_important_terms (term_id);
create index p_important_terms_project_id on project_important_terms (project_id);
