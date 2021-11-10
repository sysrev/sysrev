{ modulesPath, pkgs, ... }: {
  imports = [
    "${modulesPath}/virtualisation/amazon-image.nix"
    ./datapub-service.nix
  ];
  ec2.hvm = true;
  environment.systemPackages = with pkgs; [
    awscli
    git
    podman-compose
    (python3.withPackages( ps: with ps; [ pip setuptools ] ))
    vim
  ];
  networking.firewall.enable = false;
  security.sudo.wheelNeedsPassword = false;
  services.datapub.enable = true;
  users.groups.admin.gid = 1000;
  users.users.admin = {
    isNormalUser = true;
    uid = 1000;
    createHome = true;
    home = "/home/admin";
    extraGroups = [ "admin" "wheel" "networkmanager" ];
    openssh.authorizedKeys.keys = [ "ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAACAQDtFQCmkUCHFaptO5S/61K6ud5cfI0RKwQiIf7OpEn2oMQyOxEj4yS7XQ2ngIf+6cKlyBUbjJwPgkOuYB5/DMCvVg4oCro2kiNlvbOY1Ek/ugjGcClxK6k/JwQj+R6F+f9h/CLuQUKsvLoQ31eLMquZ2CVD26jxtJd1RsW3wPUG+aFRhcCzrHIucUbQSmH6BrGsvxj+vpUgReZNj5w2vQwnSSfJM9QC9M3dBEPPg+EXUktPlOy0bxwuMzsdr3C+j5FKFXwsyZSv9/ex/ijlAUG/CT8ao5FaHBop2BGQ5mbKfdZPJER5qoOTiv+h4ws4p4MSAD0MelOFuYpt6RLBIWBbCm1NncQxiK96z44Kzzs8ezG4SpTSidTFzGMtbykfU9uWjLKFsZDK/DX3s3roIdPgS1yO/vZbgmRO4He7mmuDVkwm86EzhfPg31jjZyiEU9WJLcJYcgm0nB3YpT1v507p/7AXDp57G8JzFJdiDutdCsR0/ZsAfqjzahCYwArwT/y84gPgA6AIkp4UVLpP+yXtUBeC7SH51Kds0lYHKdRmu7jCf1KzWoet+NQecj0oGBetkm8fG8JmSF7/X7Rmgk0tEbq+AwPffAm7Cim+HU2nIF7hOi6TFrBGW/zkDGXh6bTeZL4uU+TuA36gGwcRE549WYR+PqnkXFpeqAqnxcKH9w== john@insilica.co" ];
  };
  virtualisation = {
    podman = {
      enable = true;
    };
  };
}
