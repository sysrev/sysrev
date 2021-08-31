CREATE FUNCTION on_delete_throw_trigger() RETURNS TRIGGER AS $$
BEGIN
        IF (TG_OP = 'DELETE') THEN
           RAISE EXCEPTION 'This row cannot be deleted.';
        END IF;
        RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE FUNCTION on_update_throw_trigger() RETURNS TRIGGER AS $$
BEGIN
        IF (TG_OP = 'UPDATE') THEN
           RAISE EXCEPTION 'This row cannot be updated.';
        END IF;
        RETURN NEW;
END;
$$ LANGUAGE plpgsql;
