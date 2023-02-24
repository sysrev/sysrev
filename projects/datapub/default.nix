let
  rev = "50c23cd4ff6c8344e0b4d438b027b3afabfe58dd";
  nixpkgs = fetchTarball {
    url = "https://github.com/NixOS/nixpkgs/archive/${rev}.tar.gz";
    sha256 = "sha256:07rs6d06sg84b5nf9zwjczzbam1f3fax3s2i9kp65fdbbskqp5zs";
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
