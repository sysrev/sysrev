alter table label
      add column consensus boolean
      not null default false;

create index l_consensus_idx on label (consensus);

update label set consensus=true where name='overall include';
