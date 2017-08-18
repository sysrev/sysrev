SysRev web app
=====

This repo has a Clojure/ClojureScript project implementing the web app (server and client).

Database is shared with `systematic_review_spark` project (Scala server-side code).

Structure
===
* `project.clj` has the project definition for both the server and client projects.
* Configurations live in `config/{prod,dev}/config.edn`. You should not need to modify these files for your own development purposes. Refer to [this guide](https://github.com/yogthos/config) for details on how local, .gitignored config files or environment variables can be used to override these values for your dev environment.
* Run `sh setup.sh` to do initial setup.
* Database
    * Use `./scripts/install-flyway` to download and install Flyway inside project.
    * This creates `./flyway` script symlink (`./flyway info`, `./flyway migrate`).
    * `./scripts/reset-db` can delete an existing Postgres database, recreate it as a fresh database and load the schema from Flyway.
    * `./scripts/restore-data` can load the latest automatic backup of the production database (BACKUP_HOST="insilica-ws-1.ddns.net" or BACKUP_HOST="localhost"). Requires database to be empty with schema loaded (use `./scripts/reset-db`).
* Clojure (server)
    * Run an nginx server using the `.nginx-site` file to serve static client files and proxy AJAX requests to the Clojure web server.
    * Database connection settings are kept in `config/*/config.edn`. You can override with a local config by copying config.edn to a `.lein-env` file in `sysrev` and changing as needed.
    * Web server can be run for development with `./repl` script.
        * This will start an NREPL server for CIDER/Cursive, connect to the database and run the web server.
        * (outdated?) All code changes will be picked up immediately when compiled through REPL/IDE. The web server instance will also be restarted (very quickly) if web/core.clj is reloaded, to pick up any changes in the app definition from that file.
    * Build deployable production JAR for web server with `lein with-profile +prod uberjar`. Run the web server with `java -jar sysrev-XXXXX-standalone.jar`.
    * Use `./scripts/deploy-server` to build and send web server JAR to AWS instance and trigger restart of the process to use the new JAR.
* ClojureScript (client)
    * `./figwheel` starts a Figwheel NREPL server for the Clojurescript client project.
    * `lein cljsbuild once production` to build client project with production settings. (change `resources/public/out` symlink to `out-production` to use)
    * Use `./scripts/deploy-client` to build production client and send to AWS instance.
* AWS (production server)
    * `./systemd/` contains systemd services. `./scripts/server/` contains scripts.

Browser Tests
===

The test suite (run with `lein test`) includes browser tests using Selenium with PhantomJS. `phantomjs` needs to be installed on system and runnable via `$PATH` for these to work. It should be installed from the official binary release (phantomjs-2.1.1-linux-x86_64.tar.bz2 on http://phantomjs.org/download.html).
