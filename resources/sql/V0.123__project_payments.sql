CREATE TABLE project_payments (
       id serial PRIMARY KEY,
       charge_id text NOT NULL,
       project_id integer,
       user_id integer,
       amount integer NOT NULL,
       created integer NOT NULL
);
