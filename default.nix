let
  rev = "46725ae611741dd6d9a43c7e79d5d98ca9ce4328";
  nixpkgs = fetchTarball {
    url = "https://github.com/NixOS/nixpkgs/archive/${rev}.tar.gz";
    sha256 = "11srp3zfac0ahb1mxzkw3czlpmxc1ls7y219ph1r4wx2ndany9s9";
  };
  pkgs = import nixpkgs {};
  inherit (pkgs) fetchurl lib stdenv;
  jdk = pkgs.openjdk8;
in with pkgs;
mkShell {
  buildInputs = [
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
    nodePackages.npm # should come before nodejs for latest version
    nodejs
    polylith
    postgresql_13
    python39Packages.cfn-lint
    rlwrap
    time
  ];
  shellHook = ''
    export LD_LIBRARY_PATH="${dbus.lib}/lib:$LD_LIBRARY_PATH"
    export POSTGRES_DIRECTORY="${postgresql_13}"
    rm chrome
    ln -s ${chromium}/bin/chromium chrome
    rm scripts/clj-kondo
    ln -s ${clj-kondo}/bin/clj-kondo scripts/
  '';
}
