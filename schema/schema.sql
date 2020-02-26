create database "tron-explorer"
;

create schema if not exists analytics
;

create sequence analytics.vote_snapshot_id_seq
;

create table analytics.vote_snapshot
(
  id bigserial not null
    constraint vote_snapshot_pkey
    primary key,
  address text not null,
  timestamp timestamp with time zone default now() not null,
  votes bigint default 0 not null
)
;

create table analytics.requests
(
  id uuid not null,
  timestamp timestamp with time zone default now() not null,
  host text default ''::text,
  uri text default ''::text
)
;

create table blocks
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

create table transactions
(
  date_created timestamp with time zone,
  block bigint,
  hash text not null
    constraint transactions_hash_pk
    primary key,
  confirmed boolean default false not null,
  contract_data jsonb default '{}'::jsonb not null,
  contract_type integer default '-1'::integer not null,
  owner_address text default ''::text not null,
  to_address text default ''::text not null,
  data text default ''::text not null,
  fee bigint
)
;

create index transactions_date_created_index
  on transactions (date_created desc)
;

create index transactions_block_hash_index
  on transactions (block, hash)
;

create index transactions_date_created_owner_address_contract_type_block_ind
  on transactions (owner_address, date_created, block, contract_type)
;

create index transactions_owner_address_date_created_index
  on transactions (owner_address, date_created)
;

create table vote_witness_contract
(
  id text not null,
  transaction text,
  voter_address text,
  candidate_address text not null,
  votes bigint,
  date_created timestamp with time zone,
  block bigint,
  constraint vote_witness_contract_id_candidate_address_pk
  primary key (id, candidate_address)
)
;

create table participate_asset_issue
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

create table accounts
(
  address text not null
    constraint accounts_pkey
    primary key,
  name text default ''::text,
  balance bigint default 0,
  token_balances jsonb default '{}'::jsonb,
  date_created timestamp with time zone default now() not null,
  date_updated timestamp with time zone default now() not null,
  date_synced timestamp with time zone default now() not null,
  power bigint default 0 not null
)
;

create table asset_issue_contract
(
  id uuid
    constraint asset_issue_contract_id_pk
    unique,
  owner_address text,
  name text,
  total_supply bigint,
  trx_num integer,
  num integer,
  date_end timestamp with time zone,
  date_start timestamp with time zone,
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

create table address_balance
(
  address text,
  token text,
  balance bigint,
  constraint address_balance_address_token_pk
  unique (address, token)
)
;

create table witness_create_contract
(
  address text not null
    constraint witness_create_contract_pkey
    primary key,
  url text
)
;

create table ip_geo
(
  ip text,
  city text,
  country text,
  lat numeric,
  lng numeric
)
;

create table sr_account
(
  address text not null
    constraint sr_account_pkey
    primary key,
  github_link text
)
;

create table transfers
(
  id uuid not null
    constraint transfers_pkey
    primary key,
  date_created timestamp with time zone,
  transfer_from_address text,
  transfer_to_address text,
  amount bigint default 0,
  token_name text default 'TRX'::text,
  block bigint,
  transaction_hash text not null,
  confirmed boolean default false not null
)
;

create index transfers_transfer_from_address_transfer_to_address_date_cre
  on transfers (transfer_from_address asc, transfer_to_address asc, date_created desc)
;

create index transfers_block_hash_index
  on transfers (block, transaction_hash)
;

create index transfers_date_created_index
  on transfers (date_created desc)
;

create index transfers_transfer_from_address_date_created_index
  on transfers (transfer_from_address asc, date_created desc)
;

create index transfers_transfer_to_address_date_created_index
  on transfers (transfer_to_address asc, date_created desc)
;

create index transfers_transfer_from_address_index
  on transfers (transfer_from_address)
;

create index transfers_transfer_to_address_index
  on transfers (transfer_to_address)
;

create table trx_request
(
  address text not null
    constraint trx_request_pkey
    primary key,
  ip text not null,
  date_created timestamp with time zone default now() not null
)
;

create table funds
(
  id integer,
  address text
)
;

create table maintenance_round
(
  block bigint not null
    constraint maintenance_rounds_pkey
    primary key,
  number integer default 1,
  date_start timestamp with time zone default now(),
  date_end timestamp with time zone,
  timestamp bigint
)
;

create table round_votes
(
  address text not null,
  round integer not null,
  candidate text not null,
  votes bigint,
  constraint round_votes_address_round_candidate_pk
  primary key (address, round, candidate)
)
;

