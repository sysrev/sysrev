CREATE TABLE project_web_user_invite (
       user_id integer NOT NULL REFERENCES web_user (user_id) ON DELETE CASCADE,
       project_id integer NOT NULL REFERENCES project (project_id) ON DELETE CASCADE,
       invite_type text NOT NULL,
       accepted boolean DEFAULT FALSE,
       created timestamp WITH time zone DEFAULT now() NOT NULL,
       updated timestamp WITH time zone
);

CREATE INDEX project_web_user_invite_user_id_idx ON project_web_user_invite (user_id);
CREATE INDEX project_web_user_invite_project_id_idx ON project_web_user_invite (project_id);
