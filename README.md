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

1. Set up Nginx

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

You will need a connection to a copy of the SysRev Postgres database in order to run the server app for development. The default configuration uses port 5432 on localhost. If that port is available, open an SSH tunnel to a development machine (ws1.insilica.co) on port 5432.

There is a script included that can do this:

    ./scripts/open-tunnel insilica ws1.insilica.co 5432 5432

To use a different port number, edit these files to change the value from 5432: `config/dev/config.edn`, `config/repl/config.edn`, `config/test/config.edn`

## Dev Environment

* `./repl`
* `./figwheel`
* `lein cljsbuild once production`
* `http://localhost:80`
* `http://localhost:9010`

## IDE Setup

* Cursive (IntelliJ)
* Cider (Emacs)
* Other

## Config Files

The Clojure project uses https://github.com/yogthos/config for loading config profiles.

Config files for different profiles are kept in `config/<profile>/config.edn`.

The documentation includes ways for overriding those values locally.

## Managing Database

* `./scripts/install-flyway` to download and install Flyway inside project
* `./flyway` created as an executable script symlink
* `./scripts/clone-latest-db`

## Project Structure

* `project.clj`

### Server Project

* Server

### Client Project

* Client

### AWS Files

* `./systemd/` contains systemd services
* `./scripts/server/`

## Browser Tests

The test suite (run with `lein test`) includes browser tests using Selenium with PhantomJS. `phantomjs` needs to be installed on system and runnable via `$PATH` for these to work. It should be installed from the official binary release (phantomjs-2.1.1-linux-x86_64.tar.bz2 on <http://phantomjs.org/download.html>).
