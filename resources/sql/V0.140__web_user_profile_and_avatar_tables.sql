CREATE TABLE web_user_profile_image (
       s3_id integer NOT NULL REFERENCES s3store (id) ON DELETE CASCADE,
       user_id integer NOT NULL REFERENCES web_user (user_id) ON DELETE CASCADE,
       active boolean DEFAULT TRUE,
       meta jsonb);

CREATE TABLE web_user_avatar_image (
       s3_id integer NOT NULL REFERENCES s3store (id) ON DELETE CASCADE,
       user_id integer NOT NULL REFERENCES web_user (user_id) ON DELETE CASCADE,
       active boolean DEFAULT TRUE);
