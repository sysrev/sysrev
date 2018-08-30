ALTER TABLE annotation_web_user
DROP CONSTRAINT annotation_web_user_user_id_fkey,
ADD CONSTRAINT annotation_web_user_user_id_fkey
    FOREIGN KEY (user_id)
    REFERENCES web_user(user_id)
    ON DELETE CASCADE;
