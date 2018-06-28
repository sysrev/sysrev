CREATE TABLE annotation_label (
       annotation_id integer REFERENCES annotation(id) ON DELETE CASCADE NOT NULL,
       label_id uuid REFERENCES label(label_id) NOT NULL
);
