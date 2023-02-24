{
  description = "Datapub";

  inputs = {
    nixpkgs.url = "github:nixos/nixpkgs/nixpkgs-unstable";
    flake-utils.url = "github:numtide/flake-utils";
    flake-compat = {
      url = "github:edolstra/flake-compat";
      flake = false;
    };
  };
  outputs = { self, nixpkgs, flake-utils, ... }@inputs:
    flake-utils.lib.eachDefaultSystem (system:
      with import nixpkgs { inherit system; };
      let
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
      in {
        devShells.default = mkShell {
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
        };
      });

}
