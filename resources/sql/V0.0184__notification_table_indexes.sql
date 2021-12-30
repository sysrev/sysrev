CREATE INDEX notification_publisher_id_idx         ON notification                          (publisher_id);
CREATE INDEX notification_topic_id_idx             ON notification                          (topic_id);

CREATE INDEX notification_publisher_project_id_idx ON notification_publisher                (project_id);
CREATE INDEX notification_publisher_user_id_idx    ON notification_publisher                (user_id);
CREATE INDEX notification_publisher_created_idx    ON notification_publisher                (created);

CREATE INDEX notification_subscriber_user_id_idx   ON notification_subscriber               (user_id);
CREATE INDEX notification_subscriber_created_idx   ON notification_subscriber               (created);

CREATE INDEX notification_topic_unique_name_idx    ON notification_topic                    (unique_name);
CREATE INDEX notification_topic_created_idx        ON notification_topic                    (created);

CREATE INDEX nns_notification_id_idx               ON notification_notification_subscriber  (notification_id);
CREATE INDEX nns_subscriber_id_idx                 ON notification_notification_subscriber  (subscriber_id);
CREATE INDEX nns_viewed_idx                        ON notification_notification_subscriber  (viewed);

CREATE INDEX nst_subscriber_id_idx                 ON notification_subscriber_topic         (subscriber_id);
CREATE INDEX nst_topic_id_idx                      ON notification_subscriber_topic         (topic_id);
