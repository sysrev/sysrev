CREATE INDEX article_similarity_hi_id ON article_similarity USING btree (hi_id);
CREATE INDEX article_similarity_lo_id ON article_similarity USING btree (lo_id);
CREATE INDEX article_similarity_sim_version_fk ON article_similarity USING btree (sim_version_id);
CREATE INDEX article_similarity_similarity_idx ON article_similarity USING btree (similarity);
ALTER TABLE ONLY article_similarity
    ADD CONSTRAINT article_similarity_sim_version_id_fkey FOREIGN KEY (sim_version_id) REFERENCES sim_version(sim_version_id) ON DELETE CASCADE;
