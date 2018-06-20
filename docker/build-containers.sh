#!/usr/bin/env bash

docker build --file ./api/Dockerfile -t tronscan-api ../
docker build --file ./db/Dockerfile -t tronscan-db ../
