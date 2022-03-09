let
  rev = "47cd6702934434dd02bc53a67dbce3e5493e33a2";
  nixpkgs = fetchTarball {
    url = "https://github.com/NixOS/nixpkgs/archive/${rev}.tar.gz";
    sha256 = "1rvp9gx7n0gppc86bcysaybw79zl3y8yninsgz6rawdjprzvg7y6";
  };
  pkgs = import nixpkgs { };
  inherit (pkgs) fetchurl lib stdenv;
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
  jdk = pkgs.openjdk8;
  extensions = (with pkgs.vscode-extensions; [
    brettm12345.nixfmt-vscode
    codezombiech.gitignore
    coenraads.bracket-pair-colorizer
    editorconfig.editorconfig
    graphql.vscode-graphql
  ]) ++ pkgs.vscode-utils.extensionsFromVscodeMarketplace
    (local.vscodeExtensions or [ ] ++ [
      {
        name = "calva";
        publisher = "betterthantomorrow";
        version = "2.0.249";
        sha256 = "0xgzvh4rhdspqavsqjydr52zqpwakgfq68i890zjawa21pr1582h";
      }
      {
        name = "magit";
        publisher = "kahole";
        version = "0.6.24";
        sha256 = "16yhdavg5lgc27jc2x1rl2fmshj654dgagmhf9h6wasyhrazjxad";
      }
      {
        name = "Nix";
        publisher = "bbenoist";
        version = "1.0.1";
        sha256 = "0zd0n9f5z1f0ckzfjr38xw2zzmcxg1gjrava7yahg5cvdcw6l35b";
      }
      {
        name = "remote-ssh";
        publisher = "ms-vscode-remote";
        version = "0.74.0";
        sha256 = "1pl7l00409l9ns1ygyq7mjs87vgdb2d5nkhzrcdkjrv494525svr";
      }
      {
        name = "vscode-direnv";
        publisher = "cab404";
        version = "1.0.0";
        sha256 = "0xikkhbzb5cd0a96smj5mr1sz5zxrmryhw56m0139sbg7zwwfwps";
      }
    ]);
  vscode-with-extensions =
    pkgs.vscode-with-extensions.override { vscodeExtensions = extensions; };
in with pkgs;
mkShell {
  buildInputs = [
    awscli
    chromedriver
    chromium
    clj-kondo
    (clojure.override { jdk = jdk; })
    entr # for ./scripts/watch-css
    (flyway.override { jre_headless = jdk; })
    git
    glibcLocales # postgres and rlwrap (used by clj) need this
    jdk
    (leiningen.override { jdk = jdk; })
    lessc
    nix
    nodePackages.npm # should come before nodejs for latest version
    nodejs
    polylith
    postgresql_13
    python39Packages.cfn-lint
    rlwrap
    time
    zip

    # vscode
    clojure-lsp
    jq
    moreutils
    nixfmt
  ];
  shellHook = ''
    jq '. + {"calva.clojureLspPath": "${clojure-lsp}/bin/clojure-lsp"}' .vscode/settings.json | sponge .vscode/settings.json
    export LD_LIBRARY_PATH="${dbus.lib}/lib:$LD_LIBRARY_PATH"
    export POSTGRES_DIRECTORY="${postgresql_13}"
    echo "nix-shell --run \"${vscode-with-extensions}/bin/code -a .\"" > bin/code
    chmod +x bin/code
    rm chrome
    ln -s ${chromium}/bin/chromium chrome
    rm -f scripts/clj-kondo
    ln -s ${clj-kondo}/bin/clj-kondo scripts/
  '';
}
