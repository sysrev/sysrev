CREATE TABLE invitation (
       id serial,
       user_id integer NOT NULL REFERENCES web_user (user_id) ON DELETE CASCADE,
       project_id integer NOT NULL REFERENCES project (project_id) ON DELETE CASCADE,
       description text NOT NULL,
       accepted boolean,
       active boolean DEFAULT TRUE,
       created timestamp WITH TIME ZONE DEFAULT now() NOT NULL,
       updated timestamp WITH TIME ZONE,
       PRIMARY KEY (id)
);

CREATE TABLE invitation_from (
       invitation_id integer NOT NULL REFERENCES invitation (id) ON DELETE CASCADE,
       user_id integer NOT NULL REFERENCES web_user (user_id) ON DELETE CASCADE
);
