CREATE TABLE annotation_semantic_class (
       annotation_id integer REFERENCES annotation(id) ON DELETE CASCADE,
       semantic_class_id integer REFERENCES semantic_class(id) NOT NULL
)
