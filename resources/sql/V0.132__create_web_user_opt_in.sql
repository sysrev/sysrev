CREATE TABLE web_user_opt_in (
       user_id integer NOT NULL REFERENCES web_user (user_id) ON DELETE CASCADE,
       opt_in boolean default false,
       opt_in_type text NOT NULL,
       created timestamp WITH time zone DEFAULT now() NOT NULL,
       updated timestamp WITH time zone
);

CREATE INDEX web_user_opt_in_user_id_idx ON web_user_opt_in (user_id);
