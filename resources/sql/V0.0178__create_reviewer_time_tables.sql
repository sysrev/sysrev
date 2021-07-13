CREATE TABLE reviewer_event (
       id bigserial,
       article_id integer REFERENCES article (article_id) ON DELETE CASCADE NOT NULL,
       created timestamp WITH time zone DEFAULT now() NOT NULL,
       event_type varchar(100) NOT NULL,
       project_id integer REFERENCES project (project_id) ON DELETE CASCADE NOT NULL,
       user_id integer REFERENCES web_user (user_id) ON DELETE CASCADE NOT NULL,
       PRIMARY KEY (id)
);
