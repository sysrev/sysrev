delete from notification where content->>'type' = 'project-has-new-article';
