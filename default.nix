let
  rev = "46725ae611741dd6d9a43c7e79d5d98ca9ce4328";
  nixpkgs = fetchTarball {
    url = "https://github.com/NixOS/nixpkgs/archive/${rev}.tar.gz";
    sha256 = "11srp3zfac0ahb1mxzkw3czlpmxc1ls7y219ph1r4wx2ndany9s9";
  };
  pkgs = import nixpkgs {};
  inherit (pkgs) fetchurl lib stdenv;
  jdk = pkgs.openjdk8;
  nodeEnv = (import
    (fetchurl
      {
        url = "https://raw.githubusercontent.com/NixOS/nixpkgs/8e6b3914626900ad8f465c3e3541edbb86a95d41/pkgs/development/node-packages/node-env.nix";
        sha256 = "1ryglhc8jmnwkg4j008kl0wj1d1wb101rx0i72wz0f45y8wiwkm3";
      }
    )
    {
      lib = lib;
      libtool = pkgs.libtool;
      nodejs = pkgs.nodejs;
      python2 = pkgs.python2;
      pkgs = pkgs;
      runCommand = pkgs.runCommand;
      stdenv = stdenv;
      writeTextFile = pkgs.writeTextFile;
    }
  );
  # npm 6 and 7 are incompatible both ways, so we're pinning 6
  npm = nodeEnv.buildNodePackage {
    name = "npm";
    packageName = "npm";
    version = "6.14.15";
    src = fetchurl {
      url = "https://registry.npmjs.org/npm/-/npm-6.14.15.tgz";
      sha256 = "1iaq949s93kmhgn2f087pn20x791v4rnlmnn55i9ca7b08pznqny";
    };
    meta = {
      description = "a package manager for JavaScript";
      homepage = https://docs.npmjs.com/;
      license = "Artistic-2.0";
    };
    production = true;
    bypassCache = true;
    reconstructLock = true;
  };
in
pkgs.mkShell {
  buildInputs = with pkgs; [
    chromedriver
    chromium
    clj-kondo
    (clojure.override { jdk = jdk; })
    (flyway.override { jre_headless = jdk; })
    glibcLocales # postgres and rlwrap (used by clj) need this
    jdk
    (leiningen.override { jdk = jdk; })
    lessc
    nodejs
    npm
    polylith
    postgresql_13
    python39Packages.cfn-lint
    rlwrap
    time
  ];
  shellHook = ''
    export LD_LIBRARY_PATH="${pkgs.dbus.lib}/lib:$LD_LIBRARY_PATH"
    export POSTGRES_DIRECTORY="${pkgs.postgresql_13}"
    rm chrome
    ln -s ${pkgs.chromium}/bin/chromium chrome
    rm scripts/clj-kondo
    ln -s ${pkgs.clj-kondo}/bin/clj-kondo scripts/
  '';
}
