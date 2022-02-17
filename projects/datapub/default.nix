let
  rev = "46725ae611741dd6d9a43c7e79d5d98ca9ce4328";
  nixpkgs = fetchTarball {
    url = "https://github.com/NixOS/nixpkgs/archive/${rev}.tar.gz";
    sha256 = "11srp3zfac0ahb1mxzkw3czlpmxc1ls7y219ph1r4wx2ndany9s9";
  };
  pkgs = import nixpkgs {};
  inherit (pkgs) stdenv fetchurl;
  tessdata_best = stdenv.mkDerivation rec {
    pname = "tessdata_best";
    version = "e2aad9b983032bb1beff9133104a67cdbb87ca4d";

    src = fetchGit {
      url = "https://github.com/tesseract-ocr/tessdata_best.git";
      ref = "main";
      rev = "${version}";
    };

    installPhase = ''
      mkdir -p $out
      cp osd.traineddata eng.traineddata $out
    '';
  };
in with pkgs;
  stdenv.mkDerivation {
    name = "datapub";
    src = ./src;
    buildInputs = [
      (clojure.override { jdk = jdk; })
      glibcLocales # postgres and rlwrap (used by clj) need this
      jdk
      nix
      packer
      postgresql_13
      rlwrap
      tessdata_best
      tesseract4
    ];
    installPhase = ''
      mkdir -p $out
    '';
    shellHook = ''
      export TESSDATA_PREFIX="${tessdata_best}"
      export LD_LIBRARY_PATH="${tesseract4}/lib:$LD_LIBRARY_PATH"
      export POSTGRES_DIRECTORY="${postgresql_13}"
    '';
  }
