CREATE TABLE web_user_group (
       id serial,
       user_id integer NOT NULL REFERENCES web_user (user_id) ON DELETE CASCADE,
       group_name text NOT NULL,
       active boolean DEFAULT TRUE,
       created timestamp WITH time zone DEFAULT now() NOT NULL,
       updated timestamp WITH time zone,
       PRIMARY KEY (id)
);
