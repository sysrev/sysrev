CREATE TABLE project_group (
       id serial,
       project_id integer REFERENCES project(project_id) ON DELETE CASCADE UNIQUE NOT NULL,
       group_id integer REFERENCES groups(id) ON DELETE CASCADE NOT NULL,
       created timestamp WITH time zone DEFAULT now() NOT NULL,
       updated timestamp WITH time zone,
       PRIMARY KEY (id));
