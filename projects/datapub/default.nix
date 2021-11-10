let
  rev = "1224e4bec7b8019f8847dd268a642000073bcfa3";
  nixpkgs = fetchTarball {
    url = "https://github.com/NixOS/nixpkgs/archive/${rev}.tar.gz";
    sha256 = "1kgs6l0q84qmxdcxii88psicy8h7s2kcrkrwhlaiajbkdwk3xfx0";
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
  jdk = pkgs.openjdk11;
in with pkgs;
  stdenv.mkDerivation {
    name = "datapub";
    src = ./src;
    buildInputs = [
      (clojure.override { jdk = jdk; })
      glibcLocales # postgres and rlwrap (used by clj) need this
      jdk
      packer
      postgresql_13
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
