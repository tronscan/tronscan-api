CREATE TABLE public.trx_request
(
  address text PRIMARY KEY NOT NULL,
  ip text NOT NULL,
  date_created timestamp with time zone DEFAULT now() NOT NULL
);