# SysRev web

This repository holds the full SysRev web app (Clojure/ClojureScript project and all other files).

* [Initial Setup](#markdown-header-initial-setup)
* [Database Connection](#markdown-header-database-connection)
* [Dev Environment](#markdown-header-dev-environment)
* [IDE Setup](#markdown-header-ide-setup)
* [Config Files](#markdown-header-config-files)
* [Managing Database](#markdown-header-managing-database)
* [Project Structure](#markdown-header-project-structure)
* [Server Project](#markdown-header-server-project)
* [Client Project](#markdown-header-client-project)
* [AWS Files](#markdown-header-aws-files)
* [Browser Tests](#markdown-header-browser-tests)

## Initial Setup

1. Clone this repository

        git clone <yourname>@bitbucket.org:insilica/systematic_review.git sysrev
        cd sysrev

1. Install OpenJDK 8 (or Oracle release) via system package manager

1. Install [Leiningen](https://leiningen.org/)

1. Run project setup script

        ./setup.sh

    Follow instructions to install any missing dependencies on error, then run `setup.sh` again.

1. (Optional) Set up Nginx

    This isn't necessary for running a dev environment, but it will provide HTTP compression and replicate how the web server is set up on EC2.

    On Linux

    * Install Nginx via system package manager

    * Edit `nginx.conf` (`/etc/nginx/nginx.conf`) to include the following line:

            http {
              ...
              include /etc/nginx/sites-enabled/*;
              ...
            }
    
        And create the directory if needed:
    
            sudo mkdir -p /etc/nginx/sites-enabled

    * Link `sysrev.dev.nginx-site` into `sites-enabled`:

            sudo ln -s `pwd`/sysrev.dev.nginx-site /etc/nginx/sites-enabled/
     
    * Start Nginx process:

        (Linux systemd)

            sudo systemctl start nginx
            
            # and enable to start on boot
            
            sudo systemctl enable nginx

    On macOS

    * Install Nginx via homebrew

            brew install nginx

    * Link `sysrev.dev.nginx-site` into 'servers'

            cp sysrev.dev.nginx-site /usr/local/etc/nginx/servers/

    * Start Nginx service and restart at login

            brew services start nginx

            # if you would like to restart service

            brew services restart nginx

## Database Connection

You will need a connection to a copy of the SysRev Postgres database in order to run the server app for development. The default configuration uses port 5432 on localhost. If that port is available, you can run the web app connecting to the database via an SSH tunnel to a database machine (builds.insilica.co) on port 5432.

There is a script included that can do this:

    ./scripts/open-tunnel ubuntu builds.insilica.co 5432 5432

To use a different port number, edit these files to change the value from 5432: `config/dev/config.edn`, `config/repl/config.edn`, `config/test/config.edn`

You can also clone a local copy of the database using `./scripts/clone-latest-db` with an SSH tunnel open to a source database (`clone-latest-db` pulls from port 5470 by default; you can edit the script to change 5470 to another value if needed).

## Dev Environment

* Create local database

    1. Install wget

            `brew install wget`

    1. Install flyway

            `./scripts/install-flyway`

    1. Create a postgresql super user account

       `psql> CREATE USER postgres;`
       `psql> ALTER USER postgres WITH SUPERUSER;`

    1. Open an SSH tunnel to Postgres on a machine with a copy of the database

    1. Edit `scripts/clone-latest-db` to set `PROD_TUNNEL_PORT` to the port of
       your SSH tunnel connection to the source machine.

    1. `$ bash -c 'SR_DEST_PORT=5432 SR_DEST_DB=sysrev ./scripts/clone-latest-db'`

    1. `$ createdb -O postgres -T sysrev sysrev_test`

* `./repl` (or `M-x cider-jack-in` in Emacs) should start a REPL for the server project.
  This should automatically connect to the database and run the HTTP server when started.

* `./figwheel` should start a ClojureScript browser REPL for the client project.

* Create a web user from the Clojure REPL

  `sysrev.user> (create-user "james@insilica.co" "test1234" :project-id 100)`

## IDE Setup

* Cursive (IntelliJ)

* Cider (Emacs)

    * One of:
        * `M-x cider-connect` (connect to an external process started with `./repl` script)
        * `M-x cider-jack-in` (spawn `lein repl` process from Emacs)

## Config Files

The Clojure project uses https://github.com/yogthos/config for loading config profiles.

Config files for different profiles are kept in `config/<profile>/config.edn`.

The documentation includes ways for overriding those values locally.

## Project Structure

* `project.clj`

### Server Project

* TODO: put something here

### Client Project

[re-frame](https://github.com/Day8/re-frame) provides the core structure for the app (state management and rendering). The [re-frame documentation](https://github.com/Day8/re-frame/tree/master/docs#introduction) has a set of documents covering the rationale and use of all the core concepts (subscriptions, events, views, etc.)

The root path for ClojureScript code is `src/cljs/sysrev`. Shared client/server `.cljc` code is included from `src/cljc/sysrev`. Source paths written below are generally relative to these root paths.

#### Convention for naming of subscriptions/events

By convention for this project, auto-namespaced keywords (prefaced by `::` rather than `:`) are used for re-frame subscriptions and events that are intended to be used only in the current namespace (analogous to namespace-local functions defined using `defn-`). 

Subscriptions and events defined with ordinary globally-scoped keywords (`:get-something`) or custom-namespaced keywords (`:project/labels`) present a public interface to the rest of the project. They should aim not to duplicate similar functionality provided by other interfaces, and should avoid exposing incidental details of implemention or data formatting.

#### Organization of functionality

General-use functionality for data access and event handling is kept under `subs/` and `events/`. For functionality specific to a single UI component, the subscriptions and events should be kept in `views/` inside the file that implements rendering the component.

#### File layout structure

* `user.cljs`
    * Namespace for use in REPL. Imports symbols from all other namespaces for convenience.
* `subs/`
    * re-frame data subscriptions intended for general use.
* `events/`
    * re-frame events intended for general use.
* `data/`
    * Defines data entries fetched via GET requests from server
    * `sysrev.data.core` implements a system for defining these.
    * `sysrev.data.definitions` contains all definitions for GET requests.
* `action/`
    * Defines server interaction actions for POST requests
    * `sysrev.action.core` implements a system for defining these (similar to `sysrev.data.core`)
    * `sysrev.action.definitions` contains all definitions for POST requests.
* `routes.cljs`
    * Contains all route handler definitions.
* `views/`
    * Contains all rendering code, and state management code which is specific to a UI component.
    * `views/panels/`
        * Defines rendering handlers for all routes in the app
        * Generally organized using a separate file for each route

### AWS Files

* `./systemd/` contains systemd services
* `./scripts/server/` contains scripts that are used on the EC2 web/database server

## Browser Tests

The test suite (run with `lein test`) includes browser tests using Selenium with headless Chrome. The `chromedriver` executable must be available via `$PATH`; it should be included in your system package for Chromium or Chrome, or in an additional system package.
