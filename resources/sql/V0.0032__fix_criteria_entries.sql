update criteria
       set is_required = true
       where (project_id = 1) and (name = 'overall include');

update criteria
       set is_inclusion = false
       where (project_id = 1) and (name = 'conference abstract');
