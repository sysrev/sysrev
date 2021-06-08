DROP INDEX IF EXISTS web_user_username_key;

ALTER TABLE web_user DROP COLUMN username;

ALTER TABLE web_user ADD COLUMN username varchar(40);

-- Set default usernames based on email address.
UPDATE web_user u SET username = (
       SELECT regexp_replace(split_part(lower(email), '@', 1), '[^A-Za-z0-9]+', '-', 'g')
       FROM web_user
       WHERE user_id = u.user_id
);

-- Make sure there are no duplicate usernames.
UPDATE web_user u SET username = (
       SELECT regexp_replace(lower(email), '[^A-Za-z0-9]+', '-', 'g')
       FROM web_user
       WHERE user_id = u.user_id
) WHERE username IN (SELECT username FROM web_user GROUP BY 1 HAVING count(*) > 1);

-- Really make sure.
UPDATE web_user u SET username = (
       SELECT md5(u.email)
) WHERE username IN (SELECT username FROM web_user GROUP BY 1 HAVING count(*) > 1);

ALTER TABLE web_user ALTER COLUMN username SET NOT NULL;

CREATE UNIQUE INDEX web_user_username_key ON web_user (LOWER(username));
