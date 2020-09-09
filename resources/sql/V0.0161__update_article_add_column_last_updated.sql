ALTER TABLE article add column last_user_review timestamptz;

CREATE FUNCTION log_article_update()
    RETURNS TRIGGER
    LANGUAGE plpgsql
AS $$
BEGIN
    UPDATE article SET last_user_review = NEW.updated_time
    WHERE article.article_id = NEW.article_id AND article.last_user_review < New.updated_time;
    RETURN NEW;
END;
$$;

CREATE TRIGGER log_article_update AFTER INSERT OR UPDATE ON article_label
    FOR EACH ROW EXECUTE PROCEDURE log_article_update();


update article
    SET last_user_review=subquery.last_user_review
    FROM (SELECT article_id,max(updated_time) last_user_review from article_label GROUP BY article_id) AS subquery
    WHERE article.article_id = subquery.article_id;
