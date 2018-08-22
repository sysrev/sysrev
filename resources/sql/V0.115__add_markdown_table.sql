CREATE TABLE markdown (
       id serial PRIMARY KEY,
       string text,
       created timestamp WITH TIME ZONE NOT NULL DEFAULT now());
       
CREATE TABLE project_description (
       project_id integer REFERENCES project(project_id) ON DELETE CASCADE NOT NULL,
       markdown_id integer REFERENCES markdown(id) ON DELETE CASCADE NOT NULL);

