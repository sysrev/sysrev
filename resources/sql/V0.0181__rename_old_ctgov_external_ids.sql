update article_data
  set external_id = to_jsonb(concat('old:',external_id::text))
   where datasource_name='ctgov' and 'string' = jsonb_typeof(external_id);
