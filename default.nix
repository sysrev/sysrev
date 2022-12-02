{ pkgs ? import <nixpkgs> { } }:
let
  target = pkgs.stdenv.targetPlatform;
  rev = (if target.isDarwin then
    "60d042d7e30f35b598d244e2009d4bfc15052e04"
  else
    "97e88a936cf18bc8a2c6cf65e4ec8d423e4cb743");
  nixpkgs = fetchTarball {
    url = "https://github.com/NixOS/nixpkgs/archive/${rev}.tar.gz";
    sha256 = (if target.isDarwin then
      "sha256:0vzfy78bmz2xw6087cz8h3ycy2khw4ab39kljgsmhyfdxiwfljzm"
    else
      "sha256:1lkij94y3pxm3wdvsk4axf20g5lnm85c10r1926gwwxzp3fwqw7v");
  };
in let
  pkgs = import nixpkgs { };
  inherit (pkgs) fetchurl lib stdenv;
in with pkgs;
let
  local = if builtins.pathExists ./local.nix then import ./local.nix else { };
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
    postgresql
    python39Packages.cfn-lint
    rlwrap
    time
    yarn
    zip
  ] ++ (if target.isDarwin then [ ] else [ chromium ]);
  shellHook = ''
    export LD_LIBRARY_PATH="${dbus.lib}/lib:$LD_LIBRARY_PATH"
    echo "${vscode-with-extensions}/bin/code -a ." > bin/code
    chmod +x bin/code
    rm -f scripts/clj-kondo
    ln -s ${clj-kondo}/bin/clj-kondo scripts/
  '' + (if target.isDarwin then
    ""
  else ''
    export CHROME_BIN=${chromium}/bin/chromium
    rm -f chrome
    ln -s $CHROME_BIN chrome
  '');
}
