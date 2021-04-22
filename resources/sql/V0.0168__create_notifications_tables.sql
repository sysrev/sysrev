CREATE TABLE notification_publisher (
       publisher_id serial,
       created timestamp WITH time zone DEFAULT now() NOT NULL,
       project_id integer REFERENCES project (project_id) ON DELETE CASCADE,
       user_id integer REFERENCES web_user (user_id) ON DELETE CASCADE,
       PRIMARY KEY (publisher_id),
       UNIQUE (project_id, user_id)
);

CREATE TABLE notification_subscriber (
       subscriber_id serial,
       created timestamp WITH time zone DEFAULT now() NOT NULL,
       user_id integer NOT NULL REFERENCES web_user (user_id) ON DELETE CASCADE,
       PRIMARY KEY (subscriber_id),
       UNIQUE (user_id)
);

CREATE TABLE notification_topic (
       topic_id serial,
       created timestamp WITH time zone DEFAULT now() NOT NULL,
       unique_name varchar(126) NOT NULL,
       PRIMARY KEY (topic_id),
       UNIQUE (unique_name)
);

CREATE TABLE notification_message (
       message_id serial,
       content jsonb NOT NULL,
       created timestamp WITH time zone DEFAULT now() NOT NULL,
       publisher_id integer NOT NULL REFERENCES notification_publisher (publisher_id) ON DELETE CASCADE,
       topic_id integer NOT NULL REFERENCES notification_topic (topic_id) ON DELETE CASCADE,
       PRIMARY KEY (message_id)
);

CREATE TABLE notification_message_subscriber (
       message_id integer NOT NULL REFERENCES notification_message (message_id) ON DELETE CASCADE,
       subscriber_id integer NOT NULL REFERENCES notification_subscriber (subscriber_id) ON DELETE CASCADE,
       viewed timestamp WITH time zone,
       PRIMARY KEY (message_id, subscriber_id)
);
