DO $$
BEGIN
   execute 'alter database '||current_database()||' set default_transaction_isolation = ''serializable''';
END
$$;
