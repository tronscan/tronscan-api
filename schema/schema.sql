
create table if not exists blocks
(
  id bigint not null
    constraint blocks_pkey
    primary key,
  date_created timestamp with time zone,
  trie text,
  parent_hash text,
  witness_id bigint,
  witness_address text,
  hash text,
  size integer,
  transactions integer,
  confirmed boolean default false not null
)
;

create table if not exists transactions
(
  date_created timestamp with time zone,
  block bigint
    constraint transactions_blocks_id_fk
    references blocks
    on update cascade on delete cascade,
  hash text not null
    constraint transactions_hash_pk
    primary key,
  confirmed boolean default false not null,
  contract_data jsonb default '{}'::jsonb not null,
  contract_type integer default '-1'::integer not null,
  owner_address text default ''::text not null,
  to_address text default ''::text not null,
  data text default ''::text not null
)
;

create index if not exists transactions_date_created_index
  on transactions (date_created desc)
;

create index if not exists transactions_block_hash_index
  on transactions (block, hash)
;

create table if not exists vote_witness_contract
(
  id uuid not null
    constraint vote_witness_contract_id_pk
    primary key,
  transaction text,
  voter_address text,
  candidate_address text,
  votes bigint,
  date_created timestamp with time zone,
  block bigint
)
;

create table if not exists participate_asset_issue
(
  id uuid not null
    constraint participate_asset_issue_id_pk
    primary key,
  transaction_hash text,
  block bigint,
  date_created timestamp with time zone,
  owner_address text,
  to_address text,
  token_name text,
  amount bigint
)
;

create table if not exists accounts
(
  address text not null
    constraint accounts_pkey
    primary key,
  name text,
  balance bigint,
  token_balances jsonb,
  date_created timestamp with time zone default now() not null,
  date_updated timestamp with time zone default now() not null,
  power bigint default 0 not null
)
;

create table if not exists asset_issue_contract
(
  id uuid
    constraint asset_issue_contract_id_pk
    unique,
  transaction text,
  owner_address text,
  name text,
  total_supply bigint,
  trx_num integer,
  num integer,
  date_end timestamp with time zone,
  date_start timestamp with time zone,
  decay_ratio integer,
  vote_score integer,
  description text,
  url text,
  block bigint,
  transaction text,
  date_created timestamp with time zone default now(),
  frozen jsonb default '[]'::jsonb not null,
  abbr text default ''::text
)
;

create table if not exists address_balance
(
  address text,
  token text,
  balance bigint,
  constraint address_balance_address_token_pk
  unique (address, token)
)
;

create table if not exists witness_create_contract
(
  address text not null
    constraint witness_create_contract_pkey
    primary key,
  url text
)
;

create table if not exists ip_geo
(
  ip text,
  city text,
  country text,
  lat numeric,
  lng numeric
)
;

create table if not exists sr_account
(
  address text not null
    constraint sr_account_pkey
    primary key,
  github_link text
)
;

CREATE SCHEMA analytics;

create table if not exists analytics.vote_snapshot
(
  id bigserial not null
    constraint vote_snapshot_pkey
    primary key,
  address text not null,
  timestamp timestamp with time zone default now() not null,
  votes bigint default 0 not null
)
;

create table if not exists transfers
(
  id uuid not null
    constraint transfers_pkey
    primary key,
  date_created timestamp with time zone,
  transfer_from_address text,
  transfer_to_address text,
  amount bigint default 0,
  token_name text default 'TRX'::text,
  block bigint
    constraint transfers_blocks_id_fk
    references blocks
    on update cascade on delete cascade,
  transaction_hash text not null,
  confirmed boolean default false not null
)
;

create index if not exists transfers_transfer_from_address_transfer_to_address_date_cre
  on transfers (transfer_from_address asc, transfer_to_address asc, date_created desc)
;

create index if not exists transfers_block_hash_index
  on transfers (block, transaction_hash)
;

create index if not exists transfers_date_created_index
  on transfers (date_created desc)
;

create index if not exists transfers_transfer_from_address_date_created_index
  on transfers (transfer_from_address asc, date_created desc)
;

create index if not exists transfers_transfer_to_address_date_created_index
  on transfers (transfer_to_address asc, date_created desc)
;

create index if not exists transfers_transfer_from_address_index
  on transfers (transfer_from_address)
;

create index if not exists transfers_transfer_to_address_index
  on transfers (transfer_to_address)
;

create table if not exists analytics.requests
(
  id uuid not null,
  timestamp timestamp with time zone default now() not null,
  host text default ''::text,
  uri text default ''::text,
  referer text default ''::text not null,
  ip text default ''::text not null
)
;

create table if not exists trx_request
(
  address text not null
    constraint trx_request_pkey
    primary key,
  ip text not null,
  date_created timestamp with time zone default now() not null
)
;

create table if not exists funds
(
  id int,
  address text
)
;
