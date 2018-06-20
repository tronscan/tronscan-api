#!/usr/bin/env bash

docker build --file ./api/Dockerfile -t tronscan-api ../
docker build --file ./db/Dockerfile -t tronscan-db ../

docker tag tronscan-api tronscan/api
docker tag tronscan-db tronscan/db

docker push tronscan/api
docker push tronscan/db