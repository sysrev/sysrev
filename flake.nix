{
  description = "SysRev";

  inputs = {
    nixpkgs.url = "github:nixos/nixpkgs/nixos-23.05";
    nixpkgs-2205.url = "github:nixos/nixpkgs/nixos-22.05";
    flake-utils.url = "github:numtide/flake-utils";
    flake-compat = {
      url = "github:edolstra/flake-compat";
      flake = false;
    };
    anystyle-api = {
      url = "github:insilica/anystyle-api";
      inputs.flake-utils.follows = "flake-utils";
      inputs.nixpkgs.follows = "nixpkgs";
    };
    srvc = {
      url = "github:insilica/rs-srvc/844973dc6c45c4373d146dbfc42bbf45aee07314";
      inputs.flake-compat.follows = "flake-compat";
      inputs.flake-utils.follows = "flake-utils";
      inputs.nixpkgs.follows = "nixpkgs";
    };
  };
  outputs = { self, nixpkgs, nixpkgs-2205, flake-utils, anystyle-api, srvc, ... }@inputs:
    flake-utils.lib.eachDefaultSystem (system:
      with import nixpkgs { inherit system; };
      let
        pkgs-2205 = import nixpkgs-2205 { inherit system; };
        chrome-alias = writeShellScriptBin "chrome" ''
          ${chromium}/bin/chromium "$@"
        '';
        jdk = openjdk8;
        source = stdenv.mkDerivation {
          name = "SysRev source and docs";
          src = [
            ./bin
            ./components
            ./doc
            ./docker
            ./jenkins
            ./scripts
            ./src
            ./test
          ];
          unpackPhase = ''
            mkdir -p $out

            for dir in $src; do
              tgt=$(echo $dir | cut -d'-' -f2-)
              mkdir -p $out/$tgt
              cp -r $dir/* $out/$tgt
            done
          '';
        };
      in {
        packages = { inherit source; };
        devShells.default = mkShell {
          buildInputs = [
            anystyle-api.packages.${system}.default
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
            memcached
            nix
            nixfmt
            pkgs-2205.nodePackages.npm # should come before nodejs
            pkgs-2205.nodejs
            polylith
            postgresql
            python39Packages.cfn-lint
            rlwrap
            srvc.packages.${system}.default
            time
            yarn
            zip
          ] ++ (if stdenv.isDarwin then [ ] else [ chrome-alias chromium ]);
          # Used by the ClojureScript test runner
          CHROME_BIN =
            if stdenv.isDarwin then [ ] else [ "${chromium}/bin/chromium" ];
          LD_LIBRARY_PATH = "${dbus}/lib:"
            + (builtins.getEnv "LD_LIBRARY_PATH");
        };
      });

}
