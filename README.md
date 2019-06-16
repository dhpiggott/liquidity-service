# liquidity

[![Build Status](https://travis-ci.org/dhpiggott/liquidity-service.svg?branch=master)](https://travis-ci.org/dhpiggott/liquidity-service)
[![codecov](https://codecov.io/gh/dhpiggott/liquidity-service/branch/master/graph/badge.svg)](https://codecov.io/gh/dhpiggott/liquidity-service)

## What

Virtual currencies for Monopoly and other board and tabletop games.

## Why

I wanted to create something entirely of my own. It needed to be
something that people would want to use, and it needed to be something
that would serve as a bridge from the skillset I had to the skillset I
wanted to develop.

## Events

Liquidity has been around for nearly three years! Where has the time
gone?

The following is a partial summary.

### 2015

Inception and "MVP" launch.

Timeline:

* February
  * Finished working for Cellepathy.
  * Decided to take a break, to recover from what had been, and to learn
    new things to enable what would be.
* March
  * Playing Half Life, various bits of Yak Shaving.
* April
  * Development on Liquidity begins.
  * The very first day of experimentation showed that Wi-Fi direct was
    not going to be a viable approach. Hence there would need to a
    server component in addition to an Android client app.
  * Experience gained working for Cellepathy led me to seek an
    alternative approach to concurrency control that would not involve
    dealing with threads, locks, monitors etc.
  * Early successes with Scala for development of a log analysis tool
    for Cellepathy led me to choose it (and Play) as the basis for the
    server component.
  * Recollections of lectures on the actor model from university led me
    to investigate actor model options for Scala, which led me
    immmediately to Akka.
* March to June
  * Iterating on model and protocol (deliberately making it sufficiently
    abstract to not limit the server component's use to Monopoly -
    instead creating something that could just as well be used for e.g.
    Poker).
  * Server development completes relatively quickly.
  * Client development gets to 80% equally quickly, and the last 20%
    takes quite a bit longer - lots of polishing of the UI/UX (getting
    layouts just right across all supported form factors).
* September
  * play-json-rpc v1.0.0 released (it later gets renamed to
    scala-json-rpc, because at one point the intent was to create
    another variant for a JSON library T.B.D. which would have played
    well with the limitations re. Java 8 on Android).
  * v1.0.0 of client <> server protocol.
* October
  * Client v1.0.0 launches, followed with v1.0.1 by the end of the month.
* November
  * Client v1.0.2.

### 2016

Paid off some tech debt:

* Converted from Play to akka-http.
* Removed dependence on NGINX for TLS termination, instead doing so
  with akka-http itself.
* Switched from Java persistence serialization to JSON persistence
  serialization.
* Moved from Linode/Digital Ocean (can't remember for sure I launched
  it on) to AWS (EC2 but still just using docker-compose and rsync...)
  \- initially in eu-west-1, and then eu-west-2 (when it opened).

New functionality:

* Created analytics mechanism using persistence query API projecting
  events into Cassandra tables (chosen not because it was the most
  appropriate database for analytics, but because this allowed me to
  put theory into practice, thus gaining competence in Cassandra data
  modelling etc.).

Timeline:

* January
  * Client v1.0.3.
* July
  * Client v1.0.4.
* August
  * Client v1.1.0.
* October
  * Client v1.1.1.
  * Client v1.1.2.

### 2017

Paid off more tech debt:

* Switched from JSON persistence serialization to Protobuf.
  * Developed and used the `ProtoBinding` mechanism, using Shapeless to
    semi-automate the conversion between ScalaPB generated types and
    application types (this was motivated by the frustration I had
    experienced on a commercial prject dealing with the limitations of
    what ScalaPB can be made to generate).
* Switched from JSON-RPC wire protocol to a custom Protobuf wire
  protocol.
  * Used `ProtoBinding` here too.
  * Made of `Validated` and `NonEmptyList` for improved command
    validation and error response model.
* Removed reliance on TLS client authentication by introducing
  application level key ownership proof protocol.
* Leveraged new freedom from requiring TLS client authentication by
  switching to doing TLS termination via AWS ALB, with certificate
  provided and mananged by AWS ACM.
  * This eliminates the concern of certificate renewal.
  * It also eliminates the concern of secrets management (the
    private key for the original self-signed-and-pinned certificate was
    and is stored in Git, which was a barrier to open sourcing it).
  * Once the first client release using the v2.0 wire protocol launched
    and the v1.0 wire protocol support was subsequently disabled (after
    the vast majority of active users had upgraded), this meant that
    the Git repo could be pushed to GitHub and made open without this
    potentially compromising the authenticity, confidentiality and
    integrity of client <> server messages.
* Introduced event envelopes for retention of metadata (client remote IP
  and public key).
* Eliminated use of AtLeastOnceDelivery between `ClientConnectionActor`
  and `ZoneValidatorActor`, switching instead to a much simpler "missed
  message" detection mechanism (using sequence numbers to detect gaps
  and simply causing the client to reconnect - and thus resync its
  state).
* Switched to akka-typed - for all actors (includes use of Cluster and
  Persistence APIs for akka-typed).
* Open sourced the server.
* Setup Travis CI.
* Switch to using awslogs driver with docker-compose to publish logs to
  CloudWatch Logs (instead of the local log driver).
* Switched from Cassandra to MySQL (with doobie) for analytics
  component.
* Switched from akka-persistence-cassandra to akka-persistence-jdbc
  (MySQL) for journal and snapshots.
* Switched from MySQL on the EC2 instance to MySQL on RDS.
* Began publishing Docker images to AWS ECR as part of Travis CI (as
  opposed to pushing the stage directory generated by
  sbt-native-packager and using docker-compose's support for being
  pointed to a Dockerfile directory when manually deploying via rsync to
  EC2).
* Convert client UI glue (activities and fragments) to Kotlin.
* Port `BoardGame.scala` (part of client library published by server) to
  Android build, converting it to Kotlin in the process.
* Setup publishing of ws-protocol `.proto` files via Jar to Bintray as
  part of Travis CI.
* Setup Android build to consume published `.proto` file Jar and
  generate Java flavour bindings.
* Port `ServerConnection.scala` (part of client library published by
  server) to Android build, converting it to Kotlin in the process
  (against the generated Java ws-protocol bindings). This eliminates the
  client's dependence on any _code_ published from the server module,
  and along with it all the frustration around Android's lack of support
  for Java 8 features (which Scala 2.12 and most modern Scala libraries
  require).
* Leveraged new reduction in statefulnes and switched from rsync +
  docker-compose + EC2 to AWS ECS.
* Converted from using Akka's multi-jvm plugin for integration testing
  to publishing images and launching containers instead.

Timeline:

* February
  * Client v1.1.3.
* September
  * Client v2.0.0.
* December
  * Client v2.0.1.
  * Client v2.0.2.

### 2018

* Created CloudFormation stack for deploying and updating ECS task
  definition and service.
* Leveraged CloudFormation application stack to make Travis CI also do
  fully automatic CD from master.
* Created CloudFormation stack for deploying and updating infrastructure
  (security groups, RDS instance + dependencies, ECS instance +
  dependencies, ALB + dependencies).
* Switched to ECS awsvpc mode networking.
* Moved from eu-west-2 to us-east-1.
* Switched from ECS on EC2 to Fargate.
* Added self-signed JWT authn/z mechanism to protect access to
  diagnostics and analytics endpoints.

Timeline:

* January
  * Client v2.0.3.
  * Client v2.0.4.

## Themes

### Common

* Wire protocol
* Shared libraries

### Server

* Persistence serialization
* Persistence plugin
* Validation
* Monitoring
* Analytics
* Diagnostics
* CI/CD
* AWS - convenience and peace of mind, but at a cost

### Client

* Java starts to be quite frustrating
* Scala on Android
* Java 8 support (or lack of)
* Legacy support

## Future

T.B.D.
