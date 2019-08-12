CREATE TABLE web_event (
  event_id SERIAL PRIMARY KEY,
  event_time TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
  event_type TEXT NOT NULL,
  logged_in BOOLEAN,
  user_id INTEGER REFERENCES web_user (user_id) ON DELETE SET NULL,
  project_id INTEGER REFERENCES project (project_id) ON DELETE SET NULL,
  skey TEXT REFERENCES session (skey) ON DELETE SET NULL,
  client_ip TEXT,
  browser_url TEXT,
  request_url TEXT,
  request_method TEXT,
  is_error BOOLEAN NOT NULL DEFAULT FALSE,
  meta JSONB
);

CREATE INDEX wev_event_time_idx ON web_event (event_time);
CREATE INDEX wev_event_type_idx ON web_event (event_type);
CREATE INDEX wev_user_id_idx ON web_event (user_id);
CREATE INDEX wev_project_id_idx ON web_event (project_id);
CREATE INDEX wev_is_error_idx ON web_event (is_error);
