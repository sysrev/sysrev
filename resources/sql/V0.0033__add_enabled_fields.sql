alter table criteria add column enabled boolean default true not null;
create index c_enabled_idx on criteria (enabled);

alter table project_member add column enabled boolean default true not null;
create index pm_enabled_idx on project_member (enabled);

alter table project add column enabled boolean default true not null;
create index p_enabled_idx on project (enabled);
