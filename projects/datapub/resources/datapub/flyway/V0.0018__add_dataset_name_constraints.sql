alter table dataset add constraint dataset_name_valid
    check (created < '2022-05-06'
           OR (char_length(name) <= 40
               AND name ~ '^([A-Za-z0-9]+-)*[A-Za-z0-9]+$'));
