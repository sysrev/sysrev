--ALTER TABLE notification_publisher DROP CONSTRAINT notification_publisher_project_id_user_id_key;

--ALTER TABLE notification_publisher ADD CONSTRAINT notification_publisher_project_id_user_id_key UNIQUE (coalesce(project_id, -1), coalesce(user_id, -1));

DROP TABLE notification_publisher CASCADE;

CREATE TABLE notification_publisher (
       publisher_id serial,
       created timestamp WITH time zone DEFAULT now() NOT NULL,
       project_id integer REFERENCES project (project_id) ON DELETE CASCADE,
       user_id integer REFERENCES web_user (user_id) ON DELETE CASCADE,
       PRIMARY KEY (publisher_id),
       UNIQUE (project_id),
       UNIQUE (user_id)
);

ALTER TABLE notification_message ADD CONSTRAINT notification_message_publisher_id_fkey FOREIGN KEY (publisher_id) REFERENCES notification_publisher (publisher_id) ON DELETE CASCADE;
