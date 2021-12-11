#!/bin/sh

# This script installs the Nix package manager on your system by
# downloading a binary distribution and running its installer script
# (which in turn creates and populates /nix).

{ # Prevent execution if this script was only partially downloaded
oops() {
    echo "$0:" "$@" >&2
    exit 1
}

umask 0022

tmpDir="$(mktemp -d -t nix-binary-tarball-unpack.XXXXXXXXXX || \
          oops "Can't create temporary directory for downloading the Nix binary tarball")"
cleanup() {
    rm -rf "$tmpDir"
}
trap cleanup EXIT INT QUIT TERM

require_util() {
    command -v "$1" > /dev/null 2>&1 ||
        oops "you do not have '$1' installed, which I need to $2"
}

case "$(uname -s).$(uname -m)" in
    Linux.x86_64)
        hash=1cc3bfed96daa124a4f54063ea9fb52fa645538e2aeff93b5d411f75ae0f1b94
        path=r5ipl34p18cg162ql0br4a7xcwx77ki5/nix-2.4-x86_64-linux.tar.xz
        system=x86_64-linux
        ;;
    Linux.i?86)
        hash=16ecdf307aa3fd802350e03727b27d8937913f8328fb5c1aba35da021b84bfdb
        path=0yl4jq3yh2s4y96y1jfgm2nyyq1mp01l/nix-2.4-i686-linux.tar.xz
        system=i686-linux
        ;;
    Linux.aarch64)
        hash=0f430171c8331cb9de0dbff369d4090264cc47a9ed41f920a2f6ba6495d88e74
        path=imkb9ncqn2k7f1z4zj4hz9i8ry5cqd70/nix-2.4-aarch64-linux.tar.xz
        system=aarch64-linux
        ;;
    Linux.armv6l_linux)
        hash=8c9ed06816657aba70726ec736af7708d3f7bcce86c90741cc068b3fed5ac3af
        path=h34d7nmpbp7ngbhda5pd6gsqwiv7j6rk/nix-2.4-armv6l-linux.tar.xz
        system=armv6l-linux
        ;;
    Linux.armv7l_linux)
        hash=73b02e9a7cfc43bfde5c09119755ce4f54234fa67b9eaad97b313ce04bc19933
        path=l7mqgf3n0zrm87af49k08y0a59km6lbl/nix-2.4-armv7l-linux.tar.xz
        system=armv7l-linux
        ;;
    Darwin.x86_64)
        hash=ac4f400515380349bdbf8094346fc45d2c3dad759471a8835a8f2831b4910f35
        path=53ls42jsmi246af30vc4yhw5wg9njrip/nix-2.4-x86_64-darwin.tar.xz
        system=x86_64-darwin
        ;;
    Darwin.arm64|Darwin.aarch64)
        hash=47f4a0299a78ce396e437c2eca28d6219983730edd880f238739935a771e29e4
        path=zjz4lwz2ixq8hah734s6rp923bsbrp6b/nix-2.4-aarch64-darwin.tar.xz
        system=aarch64-darwin
        ;;
    *) oops "sorry, there is no binary distribution of Nix for your platform";;
esac

# Use this command-line option to fetch the tarballs using nar-serve or Cachix
if [ "${1:-}" = "--tarball-url-prefix" ]; then
    if [ -z "${2:-}" ]; then
        oops "missing argument for --tarball-url-prefix"
    fi
    url=${2}/${path}
    shift 2
else
    url=https://releases.nixos.org/nix/nix-2.4/nix-2.4-$system.tar.xz
fi

tarball=$tmpDir/nix-2.4-$system.tar.xz

require_util tar "unpack the binary tarball"
if [ "$(uname -s)" != "Darwin" ]; then
    require_util xz "unpack the binary tarball"
fi

if command -v wget > /dev/null 2>&1; then
    fetch() { wget "$1" -O "$2"; }
elif command -v curl > /dev/null 2>&1; then
    fetch() { curl -L "$1" -o "$2"; }
else
    oops "you don't have wget or curl installed, which I need to download the binary tarball"
fi

echo "downloading Nix 2.4 binary tarball for $system from '$url' to '$tmpDir'..."
fetch "$url" "$tarball" || oops "failed to download '$url'"

if command -v sha256sum > /dev/null 2>&1; then
    hash2="$(sha256sum -b "$tarball" | cut -c1-64)"
elif command -v shasum > /dev/null 2>&1; then
    hash2="$(shasum -a 256 -b "$tarball" | cut -c1-64)"
elif command -v openssl > /dev/null 2>&1; then
    hash2="$(openssl dgst -r -sha256 "$tarball" | cut -c1-64)"
else
    oops "cannot verify the SHA-256 hash of '$url'; you need one of 'shasum', 'sha256sum', or 'openssl'"
fi

if [ "$hash" != "$hash2" ]; then
    oops "SHA-256 hash mismatch in '$url'; expected $hash, got $hash2"
fi

unpack=$tmpDir/unpack
mkdir -p "$unpack"
tar -xJf "$tarball" -C "$unpack" || oops "failed to unpack '$url'"

script=$(echo "$unpack"/*/install)

[ -e "$script" ] || oops "installation script is missing from the binary tarball!"
export INVOKED_FROM_INSTALL_IN=1
"$script" "$@"

} # End of wrapping
