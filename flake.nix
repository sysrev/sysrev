{
  description = "SysRev";

  inputs = {
    nixpkgs.url = "github:nixos/nixpkgs/nixpkgs-unstable";
    nixpkgs-2205.url = "github:nixos/nixpkgs/nixos-22.05";
    flake-utils.url = "github:numtide/flake-utils";
    flake-compat = {
      url = "github:edolstra/flake-compat";
      flake = false;
    };
  };
  outputs = { self, nixpkgs, nixpkgs-2205, flake-utils, ... }@inputs:
    flake-utils.lib.eachDefaultSystem (system:
      with import nixpkgs { inherit system; };
      let
        pkgs-2205 = import nixpkgs-2205 { inherit system; };
        jdk = openjdk8;
      in {
        devShells.default = mkShell {
          buildInputs = [
            awscli
            chromedriver
            clj-kondo
            (clojure.override { jdk = jdk; })
            entr # for ./scripts/watch-css
            (pkgs-2205.flyway.override { jre_headless = jdk; })
            git
            glibcLocales # postgres and rlwrap (used by clj) need this
            jdk
            (leiningen.override { jdk = jdk; })
            nix
            nixfmt
            pkgs-2205.nodePackages.npm # should come before nodejs
            pkgs-2205.nodejs
            polylith
            postgresql
            python39Packages.cfn-lint
            rlwrap
            srvc
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
