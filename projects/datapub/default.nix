let
  nixpkgs = import <nixpkgs> {};
  inherit (nixpkgs) stdenv fetchurl;
  sources = import ./nix/sources.nix { };
  pkgs = import sources.nixpkgs { };
  tessdata_best = stdenv.mkDerivation rec {
    pname = "tessdata_best";
    version = "e2aad9b983032bb1beff9133104a67cdbb87ca4d";

    src = fetchGit {
      url = "https://github.com/tesseract-ocr/tessdata_best.git";
      ref = "main";
      rev = "${version}";
    };

    installPhase = ''
      cp -r . $out
    '';
  };
  jdk = pkgs.openjdk11;
in
pkgs.mkShell {
  buildInputs = [
    tessdata_best
    (pkgs.clojure.override { jdk = jdk; })
    jdk
    pkgs.tesseract4
  ];

  shellHook = ''
    export TESSDATA_PREFIX="${tessdata_best}"
    export LD_LIBRARY_PATH="${pkgs.tesseract4}/lib:$LD_LIBRARY_PATH"
  '';
}
