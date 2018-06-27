CREATE table annotation (
       id serial PRIMARY KEY,
       selection text NOT NULL,
       annotation text NOT NULL,
       created timestamp WITH TIME ZONE NOT NULL DEFAULT now());
