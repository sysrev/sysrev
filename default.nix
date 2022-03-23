{ pkgs ? import <nixpkgs> { } }:
let
  target = pkgs.stdenv.targetPlatform;
  rev = (if target.isDarwin then
    "31aa631dbc496500efd2507baaed39626f6650f2"
  else
    "0f85665118d850aae5164d385d24783d0b16cf1b");
  nixpkgs = fetchTarball {
    url = "https://github.com/NixOS/nixpkgs/archive/${rev}.tar.gz";
    sha256 = (if target.isDarwin then
      "08qaraj9j7m2g1ldhpkg8ksylk7s00mr7khkzif0m8jshkq8j92b"
    else
      "1x60c4s885zlqm1ffvjj09mjq078rqgcd08l85004cijfsqld263");
  };
in let
  pkgs = import nixpkgs { };
  inherit (pkgs) fetchurl lib stdenv;
in with pkgs;
let
  local = if builtins.pathExists ./local.nix then import ./local.nix else { };
  clj-kondo = pkgs.clj-kondo.overrideAttrs (oldAttrs: rec {
    pname = "clj-kondo";
    version = "2022.02.09";
    src = fetchurl {
      url =
        "https://github.com/clj-kondo/${pname}/releases/download/v${version}/${pname}-${version}-standalone.jar";
      sha256 = "0p6vw3i6hif90ygfcrmjbgk5s7xk2bbvknn72nrxw9dv8jgy7wsr";
    };
  });
  jdk = openjdk8;
  extensions = (with vscode-extensions; [
    bbenoist.nix
    betterthantomorrow.calva
    brettm12345.nixfmt-vscode
    codezombiech.gitignore
    coenraads.bracket-pair-colorizer
    editorconfig.editorconfig
    graphql.vscode-graphql
    kahole.magit
    ms-vscode-remote.remote-ssh
  ]) ++ vscode-utils.extensionsFromVscodeMarketplace
    (local.vscodeExtensions or [ ]);
  vscode-with-extensions =
    pkgs.vscode-with-extensions.override { vscodeExtensions = extensions; };
in with pkgs;
mkShell {
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
    postgresql_13
    python39Packages.cfn-lint
    rlwrap
    time
    yarn
    zip
  ] ++ (if target.isDarwin then [ ] else [ chromium ]);
  shellHook = ''
    export LD_LIBRARY_PATH="${dbus.lib}/lib:$LD_LIBRARY_PATH"
    export POSTGRES_DIRECTORY="${postgresql_13}"
    echo "source vars.sh && ${vscode-with-extensions}/bin/code -a ." > bin/code
    chmod +x bin/code
    rm -f scripts/clj-kondo
    ln -s ${clj-kondo}/bin/clj-kondo scripts/
  '' + (if target.isDarwin then
    ""
  else ''
    rm chrome
    ln -s ${chromium}/bin/chromium chrome
  '');
}
