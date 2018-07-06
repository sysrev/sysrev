CREATE table annotation (
       id serial PRIMARY KEY,
       selection text NOT NULL,
       annotation text NOT NULL,
       context jsonb,
       created timestamp WITH TIME ZONE NOT NULL DEFAULT now());
