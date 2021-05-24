ALTER TABLE web_user DROP COLUMN username;

ALTER TABLE web_user ADD COLUMN username varchar(40);

-- Set default usernames based on email address.
UPDATE web_user u SET username = (
       SELECT regexp_replace(split_part(lower(email), '@', 1), '[^\w\d]+', '-', 'g')
       FROM web_user
       WHERE user_id = u.user_id
);

-- Make sure there are no duplicate usernames.
UPDATE web_user u SET username = (
       SELECT regexp_replace(lower(email), '[^\w\d]+', '-', 'g')
       FROM web_user
       WHERE user_id = u.user_id
) WHERE username IN (SELECT username FROM web_user GROUP BY 1 HAVING count(*) > 1);

-- Really make sure.
UPDATE web_user u SET username = (
       SELECT md5(random()::text)
) WHERE username IN (SELECT username FROM web_user GROUP BY 1 HAVING count(*) > 1);

ALTER TABLE web_user ALTER COLUMN username SET NOT NULL;

CREATE UNIQUE INDEX web_user_username_key ON web_user (LOWER(username));
