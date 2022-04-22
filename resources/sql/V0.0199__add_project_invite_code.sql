create function base64url_encode(bytes bytea) returns text
immutable
returns null on null input
as $$ select replace(replace(replace(encode(bytes, 'base64'),'=',''),'+','-'),'/','_') $$
language sql;

alter table project add column invite_code text not null
    default base64url_encode(gen_random_bytes(24));
alter table project add constraint project_invite_code_unique
    unique (invite_code);
