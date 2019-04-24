CREATE TABLE compensation_project_default (
       compensation_id integer REFERENCES compensation(id) UNIQUE NOT NULL,
       project_id integer REFERENCES project(project_id) UNIQUE NOT NULL
);
