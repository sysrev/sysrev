let
  rev = "47cd6702934434dd02bc53a67dbce3e5493e33a2";
  nixpkgs = fetchTarball {
    url = "https://github.com/NixOS/nixpkgs/archive/${rev}.tar.gz";
    sha256 = "1rvp9gx7n0gppc86bcysaybw79zl3y8yninsgz6rawdjprzvg7y6";
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
