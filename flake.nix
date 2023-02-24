{
  description = "SysRev";

  inputs = {
    nixpkgs.url = "github:nixos/nixpkgs/nixpkgs-unstable";
    flake-utils.url = "github:numtide/flake-utils";
    flake-compat = {
      url = "github:edolstra/flake-compat";
      flake = false;
    };
  };
  outputs = { self, nixpkgs, flake-utils, ... }@inputs:
    flake-utils.lib.eachDefaultSystem (system:
      with import nixpkgs { inherit system; };
      let
        jdk = openjdk8;
      in {
        devShells.default = mkShell {
          buildInputs = [
            awscli
            chromedriver
            clj-kondo
            (clojure.override { jdk = jdk; })
            entr # for ./scripts/watch-css
            (flyway.override { jre_headless = jdk; })
            git
            glibcLocales # postgres and rlwrap (used by clj) need this
            jdk
            (leiningen.override { jdk = jdk; })
            nix
            nixfmt
            nodePackages.npm # should come before nodejs for latest version
            nodejs
            polylith
            postgresql
            python39Packages.cfn-lint
            rlwrap
            time
            yarn
            zip
          ] ++ (if stdenv.isDarwin then [ ] else [ chromium ]);
          shellHook = ''
            export LD_LIBRARY_PATH="${dbus.lib}/lib:$LD_LIBRARY_PATH"
            rm -f scripts/clj-kondo
            ln -s ${clj-kondo}/bin/clj-kondo scripts/
          '' + (if stdenv.isDarwin then
            ""
          else ''
            export CHROME_BIN=${chromium}/bin/chromium
            rm -f chrome
            ln -s $CHROME_BIN chrome
          '');
        };
      });

}
