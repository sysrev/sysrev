CREATE TABLE annotation_web_user (
       annotation_id integer REFERENCES annotation(id) ON DELETE CASCADE NOT NULL,
       user_id integer REFERENCES web_user(user_id) NOT NULL
);
