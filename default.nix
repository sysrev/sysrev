{ pkgs ? import <nixpkgs> { } }:
let
  target = pkgs.stdenv.targetPlatform;
  rev = (if target.isDarwin then
    "0a58eebd8ec65ffdef2ce9562784123a73922052"
  else
    "af0a9bc0e5341855518e9c1734d7ef913e5138b9");
  nixpkgs = fetchTarball {
    url = "https://github.com/NixOS/nixpkgs/archive/${rev}.tar.gz";
    sha256 = (if target.isDarwin then
      "067f14m6s4wpd4ahr4llj8ml883dh2wjm3r6mbiqnpj0a9sbb1vd"
    else
      "0qqxa8xpy1k80v5al45bsxqfs3n6cphm9nki09q7ara7yf7yyrh1");
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
    export CHROME_BIN=${chromium}/bin/chromium
    rm -f chrome
    ln -s $CHROME_BIN chrome
  '');
}
