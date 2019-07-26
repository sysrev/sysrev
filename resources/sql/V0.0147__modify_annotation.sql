-- create field to replace ann_semantic_class table
ALTER TABLE annotation
      ADD COLUMN semantic_class_id INTEGER
      REFERENCES semantic_class (semantic_class_id);

-- copy values of new semantic_class_id field from ann_semantic_class table
UPDATE annotation ann SET semantic_class_id = (
       SELECT semantic_class_id FROM ann_semantic_class ann_sc
       WHERE ann_sc.annotation_id = ann.annotation_id
       LIMIT 1
);

-- copied into semantic_class_id field above
DROP TABLE ann_semantic_class;

-- this duplicates existing annotation.article_id field
DROP TABLE ann_article;
