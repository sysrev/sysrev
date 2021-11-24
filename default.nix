let
  sources = import ./nix/sources.nix { };
  pkgs = import sources.nixpkgs { };
  inherit (pkgs) fetchurl lib stdenv;
  jdk = pkgs.openjdk8;
  clj-kondo = pkgs.clj-kondo.overrideAttrs( oldAttrs: rec {
    pname = "clj-kondo";
    version = "2021.04.23";
    reflectionJson = fetchurl {
      name = "reflection.json";
      url = "https://raw.githubusercontent.com/clj-kondo/${pname}/v${version}/reflection.json";
      sha256 = "0412yabsvhjb78bqj81d6c4srabh9xv5vmm93k1fk2abk69ii10b";
    };
    src = fetchurl {
      url = "https://github.com/clj-kondo/${pname}/releases/download/v${version}/${pname}-${version}-standalone.jar";
      sha256 = "1y9jlhcivcwpkb3dwpiba16wvq7irpsvg003lzgxcv0wpn1d23av";
    };
    buildPhase = ''
      native-image  \
        -jar ${src} \
        -H:Name=clj-kondo \
        ${lib.optionalString stdenv.isDarwin ''-H:-CheckToolchain''} \
        -H:+ReportExceptionStackTraces \
        -J-Dclojure.spec.skip-macros=true \
        -J-Dclojure.compiler.direct-linking=true \
        "-H:IncludeResources=clj_kondo/impl/cache/built_in/.*" \
        -H:ReflectionConfigurationFiles=${reflectionJson} \
        --initialize-at-build-time  \
        -H:Log=registerResource: \
        --verbose \
        --no-fallback \
        --no-server \
        "-J-Xmx3g"
    '';
  });
  nodeEnv = (import
    (fetchurl
      {
        url = "https://raw.githubusercontent.com/NixOS/nixpkgs/nixos-21.05/pkgs/development/node-packages/node-env.nix";
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
    version = "6.14.8";
    src = fetchurl {
      url = "https://registry.npmjs.org/npm/-/npm-6.14.8.tgz";
      sha256 = "0b2s3zw0yzs7y6xc1j3pyqkqjb0jp5g75d36yrknzh06nqy8g3py";
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
  # polylith is still in the unstable channel, so we import it from there
  polylith = (import
    (fetchurl
      {
        url = "https://raw.githubusercontent.com/NixOS/nixpkgs/e4ef597edfd8a0ba5f12362932fc9b1dd01a0aef/pkgs/development/tools/misc/polylith/default.nix";
        sha256 = "0fl28x7613090wpr956vwaxdsmyxwcmz78ir5mkc15c3zj49zhhz";
      }
    )
    {
      fetchurl = fetchurl;
      jdk = jdk;
      lib = lib;
      stdenv = stdenv;
      runtimeShell = pkgs.runtimeShell;
    }
  ).overrideAttrs( oldAttrs: rec {
    version = "0.2.13-alpha";
    src = fetchurl {
      url = "https://github.com/polyfy/polylith/releases/download/v${version}/poly-${version}.jar";
      sha256 = "08m1lslv1frfg723px35w6hlccgrl031yywvsa1wywxbmgd7vcw8";
    };
  });
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
    time
  ];
  shellHook = ''
    export LD_LIBRARY_PATH="${pkgs.dbus.lib}/lib:$LD_LIBRARY_PATH"
    export POSTGRES_DIRECTORY="${pkgs.postgresql_13}"
    rm chrome
    ln -s ${pkgs.chromium}/bin/chromium chrome
    rm scripts/clj-kondo
    ln -s ${clj-kondo}/bin/clj-kondo scripts/
  '';
}
