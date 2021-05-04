CREATE TABLE notification_subscriber_topic (
       created timestamp WITH time zone DEFAULT now() NOT NULL,
       subscriber_id integer NOT NULL REFERENCES notification_subscriber (subscriber_id) ON DELETE CASCADE,
       topic_id integer NOT NULL REFERENCES notification_topic (topic_id) ON DELETE CASCADE,
       PRIMARY KEY (subscriber_id, topic_id)
);
