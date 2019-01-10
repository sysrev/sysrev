CREATE TABLE web_user_email (
       id serial,
       user_id integer NOT NULL REFERENCES web_user (user_id) ON DELETE CASCADE,
       verify_code text NOT NULL,
       email text NOT NULL,
       verified boolean NOT NULL DEFAULT FALSE,
       principal boolean DEFAULT FALSE,
       created timestamp WITH TIME ZONE DEFAULT now() NOT NULL,
       updated timestamp WITH TIME ZONE,
       PRIMARY KEY (id)
 );
