Systematic Review
=====

This has a Scala project for importing articles data and running Spark ML on it, and a Clojure project implementing a web app (server and client).

Both projects share a PostgreSQL database that holds all data.

Project Features
===
Checkboxes indicate implemented features:

* :white_large_square: Users can create a new project
    * Add inclusion / exclusion label types / classifications (Project Phase 1)
    * Add statistics input types (Project Phase 2)
    * Upload endnote enlx file
    * Users can select or define a review strategy, e.g.
        * Two users minimum for each entity
        * One more user for resolving disputes
            * Should that user see what the other user indicated?
            * Is the resolver a special user?
* :white_large_square: Users can invite other users to join the project
* :white_check_mark: Users can come to the site and classify entities
    * One entity classified at a time
    * Classifications made permanent with confirmation
    * Classifications left provisional before confirmation


Structure
===
* Scala (database and ML)
    * `systematic_review/core` This imports an xml file of articles exported from EndNote, into a Postgres database, and provides queries on the database.
    * `systematic_review_spark` This is a separate repo which has Spark functionality and saves results of the Spark algorithms to Postgres.
    * `resources/config.json` has settings for database connections and file paths.
* Clojure/ClojureScript (web server and client)
    * Located under `/sysrev-web` in this repo.
    * `/sysrev-web/project.clj` has the project definition for both the server and client projects.
    * Setup and use:
        * In `sysrev-web`, run `sh setup.sh` to do initial setup.
        * Run an nginx server using the `.nginx-site` file to serve static client files and proxy AJAX requests to the Clojure web server.
        * Database connection settings are kept in `config/*/config.edn`. You can override with a local config by copying config.edn to a `.lein-env` file in `sysrev-web` and changing as needed.
        * Web server can be run for development with `lein with-profile +dev repl` in `sysrev-web` directory.
            * This will start an NREPL server for CIDER/Cursive, connect to the database and run the web server.
            * All code changes will be picked up immediately when compiled through REPL/IDE. The web server instance will also be restarted (very quickly) if web/core.clj is reloaded, to pick up any changes in the app definition from that file.
        * Build deployable production JAR for web server with `lein with-profile +prod uberjar`. Run the web server with `java -jar sysrev-XXXXX-standalone.jar`.
        * `lein figwheel` starts a Figwheel NREPL server for the Clojurescript client project.
        * `lein cljsbuild once production` to build client project with production settings. (change `resources/public/out` symlink to `out-production` to use)
        * Configurations live in `config/{prod,dev}/config.edn`. You should not need to modify these files for your own
        development purposes. Refer to [this guide](https://github.com/yogthos/config) for details on how local, .gitignored config files or environment variables can be used to override these values for your dev environment.

Outline
==========
1. We have ~20,000 articles loaded into mongo database *SystematicReview* collection *sysrev*.  These are loaded from endnote file **PMP1C_Master.enlx**
2. We have 61 articles with binary exclusion/inclusion features **Immunotherapy Phase1_Selected screened abstracts_20160629.xlsx**
    1. *Inclusion:* True if all other exclusion criteria are false
    2. *Exclusion Not cancer*
    3. *Exclusion Not human study*
    4. *Exclusion Not clinical trial*
    5. *Exclusion Not phase 1 trial*
    6. *Exclusion Conference abstract*
3. Goal
    1. Rank all 20,000 articles for their of having feature 1 **Inclusion** = true
4. Approach
    1. Create classifier for **Inclusion** feature
        1. Instance based learning
            1. Article to vector (word count vector works)
                1. tf-idf vector
                2. custom features (isCancerMentioned, isHumanMentioned, timesCancerMentioned)

            2. vector similarity metric
            3. IBL algorithm
        2.
    3. Algorithm improvement approach
        1. Human label papers with closest to 50% probability of **Inclusion** = true