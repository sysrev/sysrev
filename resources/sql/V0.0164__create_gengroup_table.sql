CREATE TABLE gengroup (
  gengroup_id serial,
  name text NOT NULL,
  description text,
  active boolean DEFAULT TRUE,
  created timestamp WITH time zone DEFAULT now() NOT NULL,
  updated timestamp WITH time zone,
  PRIMARY KEY (gengroup_id)
);

CREATE TABLE project_member_gengroup (
  id serial,
  project_id integer REFERENCES project(project_id) ON DELETE CASCADE NOT NULL,
  gengroup_id integer REFERENCES gengroup(gengroup_id) ON DELETE CASCADE NOT NULL,
  PRIMARY KEY (id)
);

CREATE TABLE project_member_gengroup_member (
  project_id integer REFERENCES project(project_id) ON DELETE CASCADE NOT NULL,
  gengroup_id integer REFERENCES gengroup(gengroup_id) ON DELETE CASCADE NOT NULL,
  membership_id integer REFERENCES project_member(membership_id) ON DELETE CASCADE NOT NULL,
  PRIMARY KEY (project_id, gengroup_id, membership_id)
);

DROP TABLE project_member_gengroup_member;
DROP TABLE project_member_gengroup;
DROP TABLE gengroup;
