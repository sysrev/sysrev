CREATE TABLE semantic_class (
       id SERIAL PRIMARY KEY,
       definition jsonb NOT NULL,
       created TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);
