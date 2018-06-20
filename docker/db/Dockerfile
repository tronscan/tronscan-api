FROM postgres:9.6
MAINTAINER Rovak

ENV POSTGRES_PASSWORD=postgres
ENV POSTGRES_DB=tronscan
ENV POSTGRES_USER=postgres

ADD ./schema/schema.sql /docker-entrypoint-initdb.d/schema.sql
