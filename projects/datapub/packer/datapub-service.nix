{ config, lib, ... }:

# https://stackoverflow.com/a/38635298

with lib;

let
  cfg = config.services.datapub;
in {
  options = {
    services.datapub = {
      enable = mkOption {
        type = types.bool;
        default = false;
        description = "Whether to enable datapub.";
      };
    };
  };

  config = mkIf cfg.enable {
    systemd.services.datapub = {
      wantedBy = [ "multi-user.target" ];
      after = [ "local-fs.target" "network.target" ];
      serviceConfig = {
        Group = "admin";
        User = "admin";
        Restart = "always";
        RestartSec = 1;
        StandardOutput = "journal";
        Environment = "PATH=/run/wrappers/bin:/run/current-system/sw/bin";
        WorkingDirectory = "/home/admin/datapub";
        ExecStart = "/run/current-system/sw/bin/nix-shell --run \"./run.sh\"";
      };
    };
  };
}
