CREATE TABLE compensation (
       id serial PRIMARY KEY,
       rate jsonb,
       created timestamp WITH TIME ZONE NOT NULL DEFAULT now()
);

COMMENT ON TABLE compensation IS 'Treated as an immutable record of the rates of compensation on SysRev.';


CREATE TABLE compensation_project (
       compensation_id integer REFERENCES compensation(id),
       project_id integer REFERENCES project(project_id),
       active boolean default true,
       created timestamp WITH TIME ZONE NOT NULL DEFAULT now()
);

COMMENT ON TABLE compensation_project IS 'Compensations that are, or have been, associated with a project';

CREATE TABLE compensation_user_period (
       compensation_id integer REFERENCES compensation(id),
       web_user_id integer REFERENCES web_user(user_id),
       period_begin timestamp WITH TIME ZONE NOT NULL DEFAULT now(),
       period_end timestamp WITH TIME ZONE
);

COMMENT ON TABLE compensation_user_period IS 'Treated as an immutable record of compensation for a user during a time period ';
