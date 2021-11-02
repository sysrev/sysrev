let
  sources = import ./nix/sources.nix { };
  pkgs = import sources.nixpkgs { };
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
      cp -r . $out
    '';
  };
  jdk = pkgs.openjdk11;
in
pkgs.mkShell {
  buildInputs = with pkgs; [
    tessdata_best
    (clojure.override { jdk = jdk; })
    glibcLocales # postgres and rlwrap (used by clj) need this
    jdk
    postgresql_13
    tesseract4
  ];

  shellHook = ''
    export TESSDATA_PREFIX="${tessdata_best}"
    export LD_LIBRARY_PATH="${pkgs.tesseract4}/lib:$LD_LIBRARY_PATH"
    export POSTGRES_DIRECTORY="${pkgs.postgresql_13}"
  '';
}
