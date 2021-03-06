# Discovery Environment Services

This project contains all of the code used in the Discovery Environment backend. That includes internally developed libraries, tools, services, database schemas and migrations, and build tool plugins.

All of the code in this project was previously available as separate git repositories, but we decided to consolidate everything to make it easier to navigate.

The top-level directories __lein-plugins__, __libs__, __services__, __tools__, and __databases__ are the categories that the code falls into. Each direct subdirectory contains a separate project. For instance, __services/Donkey/__ contains the service that the Discovery Environment UI interacts with directly. It's a complete Leiningen project in and of itself.

## Development environment setup

You will need the following tools and plugins to develop for the DE backend.

* __JDK 7__: The backend code should work fine on either Oracle's JDK or on the OpenJDK.
* __Leiningen__: Clojure multi-tool. See http://leiningen.org/.
* __Maven 3__: Unfortunately not all of the backend code is in Clojure.
* __lein-exec__: A plugin for running stand-alone Clojure scripts. See https://github.com/kumarshantanu/lein-exec.
* __lein-midje__: A Clojure test framework. See https://github.com/marick/Midje.

For kifshare development, you will also need the following tools:

* __node.js__: A javascript runtime environment.
* __npm__: The node.js package manager
* __grunt__: A build tool for javascript projects.

The easiest way to get the above on OS X is with homebrew:

    brew install npm
    npm install -g grunt-cli
    npm install -g grunt

If you intend to build the services with the build-all.clj script, you __will__ need those tools installed.

## Building everything

We've provided a Clojure script that will go through all of the projects, build them, and move the builds to a builds/ directory. You run it like this from the top level of this project:

    lein exec build-all.clj

If you just want to create the Leiningen-compatible checkout symlinks, run the following command:

    lein exec build-all.clj symlinks

To build just the services, run:

    lein exec build-all.clj services

The rest:

    lein exec build-all.clj tools
    lein exec build-all.clj lein-plugins
    lein exec build-all.clj libs
    lein exec build-all.clj databases

To archive the builds into the builds/ directory, add the --archive option to the above commands.

    lein exec build-all.clj tools --archive

## Building a specific project

For the Leiningen projects, you'll want to create the checkouts symlinks first. They help make sure that you're developing against the latest version of our libraries.

    lein exec build-all.clj symlinks

If you want to just build a specific project, go into the project's directory and call the appropriate build tool. For example:

    cd services/Donkey
    lein clean
    lein uberjar

## Setting and getting a version for everything

We've hacked together a bash script that will iterate over all of the Clojure projects and will set their versions to the same version, including the versions of the iPlant developed dependencies (aside from metadactyl).

    ./set-version 2.0.10

Please, run that against a branch and submit a pull request.

We've also hacked a script together to report the versions of Clojure projects.

    ./get-versions

Again, these were hacked together extremely quickly and still have sharp edges. Be careful and do all modifications in a branch.
