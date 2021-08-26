# datapub

## Run

You will need to have the [Clojure CLI tools](https://clojure.org/guides/getting_started#_clojure_installer_and_cli_tools) installed.

Run `./repl.sh` to run the development server. Or start a REPL from your editor, and run

```clj
((requiring-resolve 'datapub.main/reload-with-fixtures!))
```

Visit http://localhost:8888/ide.

The development server runs an embedded database with some data pre-loaded to facilitate development and testing. If you want to set up a persistent database and connect to that, set DBHOST and DBPORT and make sure that DBEMBEDDED is unset.

## Test

Run `./test.sh`, or use your editor's hotkeys.

## Deploy

Run `./deploy.sh`.

The production server lives at https://www.datapub.dev/ide. You will need to send an `Authorization: Bearer xxxx` header, where xxxx is the SYSREV_DEV_TOKEN.

## Libraries

[Component](https://github.com/stuartsierra/component) is used to manage runtime state. All global state is contained within a single atom, `datapub.main/system`. Component also makes it easy to create multiple systems with completely separate state, as the tests do.

[Lacinia](https://lacinia.readthedocs.io/en/latest/overview.html) is used to provide GraphQL. We use an SDL schema because we want to be able to support federation, and lacinia requires SDL for that.

[Lacinia-Pedestal](https://lacinia.readthedocs.io/en/latest/tutorial/pedestal.html) is used because we need its subscription support to stream large result sets.

## SQL Schema

![sql-schema](https://github.com/insilica/datapub/blob/master/doc/images/sql-schema.png?raw=true)