# SysRev web

This repository holds the full SysRev web app (Clojure/ClojureScript project and all other files).

* [Initial Setup](#markdown-header-initial-setup)
* [Database Connection](#markdown-header-database-connection)
* [Dev Environment](#markdown-header-dev-environment)
* [IDE Setup](#markdown-header-ide-setup)
* [Config Files](#markdown-header-config-files)
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

* When a route is changed, you must restart the web server

    `repl> (sysrev.init/start-app)`

* when running tests in the repl that use default-fixture , reset the web-asset-path

```clojure
repl> (sysrev.web.index/set-web-asset-path)
"/out"
```

* You can switch databases for the app
```clojure
repl> (sysrev.init/start-app {:dbname "sysrev_test"})
true
```

* To manage database with Flyway:

    Edit `flyway.conf` to match database connection settings.

    Database name `sysrev` is used in production and REPL; `sysrev_test` is used by `lein test` and Jenkins build tests.

    Run `./flyway info` to check status of database relative to files in `./resources/sql/*.sql`.

    Run `./flyway migrate` to apply changes to database; you will want to apply changes to both `sysrev`
    and `sysrev_test` (edit `flyway.conf` to connect to each).

* Log in as any user

    Use the password 'override' to login as any user in dev environment

* AWS Credentials for dev environment

	Request the config.local.edn from a developer. Copy this file into config/dev/ and config/test/


## IDE Setup

* Cursive (IntelliJ)

* Cider (Emacs)

    * One of:
        * `M-x cider-connect` (connect to an external process started with `./repl` script)
        * `M-x cider-jack-in` (spawn `lein repl` process from Emacs)

### ClojureScript client setup

1. Start figwheel using the script in the command line

    `$ ./figwheel`

1. Open a web browser

1. Note the port with the line 'Figwheel: Starting nREPL server on port: 7888'
   (project.clj sets this to port 7888 by default)

1. M-x cider-connect (use localhost and port 7888)

    Note: You must be visiting a file that is in the root dir of
    the project in order for M-. to follow fn names properly.
    It is a good idea to run "cider-connect" while visiting project.clj
    in the root dir

1. In the repl, run

```clojure
user> (use 'figwheel-sidecar.repl-api)
user> (cljs-repl)
```

1. Verify that you are communicating with the browser by running

```clojurescript
cljs.user> (.log js/console "hi")
```

    You should see "hi" in the console

1. Switch to sysrev.user namespace

```clojurescript
cljs.user> (in-ns 'sysrev.user)
```

   This is a namespace which pulls in all other namespaces as a workshop ns

1. You can use the figwheel REPL to navigate to views with the nav fn

```clojurescript
sysrev.user> (nav "/create-project")
```

### Development

1. To update reframe data, that is defined by def-data in sysrev.data.definitions,

```clojurescript
(dispatch [:fetch [:identity]])
```

2. sysrev.action.definitions for post calls

3. Re-frame keeps all data in a  reframe.db/app-db reagent atom

   Explore it with

```clojurescript
(-> @re-frame.db/app-db keys)
```

   View data with a cursor

```clojurescript
(first @(reagent.core/cursor re-frame.db/app-db [:state :self :projects]))
```

#### Development Cycle

1. To pull data from the server, add an entry into 'data/definitions.cljs'

    ex: To make a GET request on the server, using term as a URL parameter, you would use:

    bug: If you are viewing a bar chart, reloading 'data/definitions.cljs' will result in
    a browser error

```clojurescript
(def-data :pubmed-query
  :loaded? (fn [db search-term]
             (get-in db [:data :search-term search-term])
             ) ;; if loaded? is false, then data will be fetched from server, otherwise, no data is fetched. It is a fn of the dereferenced re-frame.db/app-db.
  :uri (fn [] "/api/pubmed/search") ;; uri is a function that returns a uri string
  :prereqs (fn [] [[:identity]]) ;; a fn that returns a vector of def-data entries
  :content (fn [search-term] {:term search-term}) ;; a fn that returns a map of http parameters (in a GET context)
  :process
  (fn [_ [search-term] {:keys [pmids]}]
    ;; [re-frame-db query-parameters (:result response)]
    (let [search-term-result (-> pmids :esearchresult :idlist)]
      {:dispatch-n
       (list [:pubmed/save-search-term-results search-term search-term-result])})))
```

1. Create a new event in events/<filename>.cljs for retrieving the data

```clojurescript
(reg-event-db
 :pubmed/save-search-term-results
 [trim-v]
 (fn [db [search-term search-term-response]]
   (assoc-in db [:data :search-term search-term]
             search-term-response)))
```

1. Read the data from the server in the REPL

```clojurescript
`sysrev.user> (dispatch [:fetch [:pubmed-query "foo bar"]])`
```

1. Check to see if the ajax request is ongoing,

```clojurescript
sysrev.user> @(subscribe [:loading? [:pubmed-query "foo bar"]])
false
```

1. In subs/ dir, find a relevant namespace or create a new one.

    If you create a new namespace, add it to sysrev.subs.all

```clojurescript
(reg-sub
 :pubmed/search-term-result
 (fn [db [_ search-term]] ;; first term in the destructed term is the subscription name itself,
     e.g. [_ search-term] _ is :pubmed/search-term-result
   (-> db :data :search-term (get-in [search-term]))))
```

1. The subscription makes the db atom available like this:
```clojurescript
   sysrev.user> @(subscribe [:pubmed/search-term-result "foo bar"])
```

1. Create a new view in cljs/sysrev/views{/panels}
```clojurescript
(defn SearchPanel [state]
  "A panel for searching pubmed"
  (let [current-search-term (r/cursor state [:current-search-term])
        on-change-search-term (r/cursor state [:on-change-search-term])
        page-number (r/cursor state [:page-number])]
    (fn [props]
      (let []
        [:div.create-project
         [:div.ui.segment
          [:h3.ui.dividing.header
           "Create a New Project"]
          [SearchBar state]
          [PubmedSearchLink state]
          [SearchResult state]]]))))
```

1. If a new namespace was created, add it to sysrev.views.main

1. Add a method to panel-content so that the :set-active-panel event
   can be dispatched in routes.cljs (below)
```clojurescript
(defmethod panel-content [:create-project] []
  (fn [child]
    [SearchPanel state]))
```

1. Add a route for the view (if needed) to cljs/sysrev/routes.cljs
```clojurescript
(sr-defroute
 create-project "/create-project" []
 (dispatch [:set-active-panel [:create-project]]
           "/create-project"))
```

1. If an event should change the route, add it to load-default-panels
```clojurescript
(defn- load-default-panels [db]
  (->> [[[]
         "/"]

		...

        [[:project :project :add-articles]
         "/project/add-articles"]

		...

		[[:user-settings]
         "/user/settings"]]
       (reduce (fn [db [prefix uri]]
                 (set-subpanel-default-uri db prefix uri))
               db)))
```
1. From the repl
---
<!-- 1. Define data in 'subs/' -->

<!-- 1. Define data retrieval and handling in 'data/definitions.cljs' -->

<!-- 1. Create or modify a reagent component in 'views/' -->

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
