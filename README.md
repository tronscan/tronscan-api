<h1 align="center">
  <br>
  <img width="40%" src="https://raw.githubusercontent.com/tron-explorer/docs/master/images/tron-banner.png">
  <br>
  Tronscan API
  <br>
</h1>

<h4 align="center">
  API for <a href="https://tronscan.org">Tronscan.org</a>
</h4>

<p align="center">
  <a href="#requirements">Requirements</a> •
  <a href="#installation">Installation</a> •
  <a href="https://tronscan.org">tronscan.org</a>  •
  <a href="https://api.tronscan.org">api.tronscan.org</a>
</p>

## Intro

Tronscan API provides data for the Tronscan Frontend. Tronscan API is built with Scala using Play Framework and Akka Streams.

The non-blocking architecture of Play combined with the reactive streams of Akka provides a high performance synchronisation system.

## Features

* Import of the blockchain from Full/Solidity Node
* Swagger UI for API documentation
* EHCache / Redis Caching
* Socket.io API provides a stream of events from the blockchain
* Network Scanner scans the network using GRPC and hops between nodes to find all the nodes in the network
* GRPC Load Balancer uses data provided from the network scanner to find the fastest nodes to communicate with
* Vote Scraper makes periodic snapshots of the votes to provide historical vote data
* (Beta) Remote Device signing provides a websocket API to sign transactions from an external device

# Requirements

* PostgreSQL 9.6
* Java 8
* [SBT](https://www.scala-sbt.org/1.0/docs/Installing-sbt-on-Linux.html)

# Installation

* Create a new database in PostgreSQL, for example `tron-explorer`
* Create the schema by running the [schema script](schema/schema.sql)
* Change the `slick.dbs.default` and `db.default` connection details in [application.conf](conf/application.conf)

# Running

* Run `sbt run`