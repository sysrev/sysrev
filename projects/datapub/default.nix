let
  rev = "97e88a936cf18bc8a2c6cf65e4ec8d423e4cb743";
  nixpkgs = fetchTarball {
    url = "https://github.com/NixOS/nixpkgs/archive/${rev}.tar.gz";
    sha256 = "sha256:1lkij94y3pxm3wdvsk4axf20g5lnm85c10r1926gwwxzp3fwqw7v";
  };
  pkgs = import nixpkgs { };
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
    clojure
    glibcLocales # postgres and rlwrap (used by clj) need this
    jdk
    nix
    packer
    postgresql
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
  '';
}
