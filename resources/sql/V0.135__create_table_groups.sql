CREATE TABLE groups (
       id serial,
       group_name text NOT NULL,
       active boolean DEFAULT TRUE,
       created timestamp WITH time zone DEFAULT now() NOT NULL,
       updated timestamp WITH time zone,
       PRIMARY KEY (id)
);

ALTER TABLE web_user_group DROP COLUMN group_name;
ALTER TABLE web_user_group ADD COLUMN group_id integer NOT NULL;
ALTER TABLE web_user_group ADD CONSTRAINT web_user_group_group_id_fkey FOREIGN KEY (group_id) REFERENCES groups (id) ON DELETE CASCADE;
