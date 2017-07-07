Overview of SysRev client project
=====

(re-frame)[https://github.com/Day8/re-frame] provides the core structure for the app (state management and rendering). The (re-frame documentation)[https://github.com/Day8/re-frame/tree/master/docs#introduction] has a set of documents covering the rationale and use of all the core concepts (subscriptions, events, views, etc.)

The root path for ClojureScript code is `src/cljs/sysrev`. Shared client/server `.cljc` code is included from `src/cljc/sysrev`. Source paths written below are generally relative to these root paths.

Convention for naming of subscriptions/events
===

By convention for this project, auto-namespaced keywords (prefaced by `::` rather than `:`) are used for re-frame subscriptions and events that are intended to be used only in the current namespace (analogous to namespace-local functions defined using `defn-`). 

Subscriptions and events defined with ordinary globally-scoped keywords (`:get-something`) or custom-namespaced keywords (`:project/labels`) present a public interface to the rest of the project. They should aim not to duplicate similar functionality provided by other interfaces, and should avoid exposing incidental details of implemention or data formatting.

Organization of functionality
===

General-use functionality for data access and event handling is kept under `subs/` and `events/`. For functionality specific to a single UI component, the subscriptions and events should be kept in `views/` inside the file that implements rendering the component.

File layout structure
===

* `subs/`
    * re-frame data subscriptions intended for general use.
* `events/`
    * re-frame events intended for general use.
* `views/`
    * Contains all rendering code, and state management code which is specific to a single UI component.
* `routes.cljs`
    * Contains all route handler definitions.
* `user.cljs`
    * Namespace for use in REPL. Imports symbols from all other namespaces for convenience.
