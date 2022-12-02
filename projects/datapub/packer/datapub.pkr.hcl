variable "git-ref" {
  type    = string
  default = ""
}

variable "git-sha" {
  type    = string
  default = ""
}

variable "uuid" {
  type    = string
  default = ""
}

data "amazon-ami" "previous_build" {
  filters = {
    state                   = "available"
    "tag:sysrev:build:name" = "datapub"
  }
  most_recent = true
  owners      = ["self"]
  region      = "us-east-1"
}

locals { timestamp = regex_replace(timestamp(), "[- TZ:]", "") }

source "amazon-ebs" "datapub" {
  ami_name      = "Datapub ${local.timestamp}"
  instance_type = "t3a.large"
  launch_block_device_mappings {
    delete_on_termination = true
    device_name           = "/dev/xvda"
    volume_size           = 20
    volume_type           = "gp3"
  }
  region            = "us-east-1"
  shutdown_behavior = "terminate"
  # Ubuntu was failing ~50% of the time due to apt-get not finding packages,
  # so we're using Debian.
  #source_ami        = "${data.amazon-ami.previous_build.id}"
  source_ami        = "ami-0be49b0e69a32b6bb"
  ssh_username      = "admin"
  tags = {
    "sysrev:build:git-ref" = "${var.git-ref}"
    "sysrev:build:git-sha" = "${var.git-sha}"
    "sysrev:build:name"    = "datapub"
    "sysrev:build:uuid"    = "${var.uuid}"
  }
}

build {
  sources = ["source.amazon-ebs.datapub"]

  provisioner "file" {
    destination = "/tmp/"
    source      = "./datapub.service"
  }

  provisioner "file" {
    destination = "/tmp/"
    source      = "../default.nix"
  }

  provisioner "file" {
    destination = "/tmp/"
    source      = "./run.sh"
  }

  provisioner "file" {
    destination = "/tmp/"
    source      = "../target/datapub.jar"
  }

  provisioner "shell" {
    script = "./kill-apt-services.sh"
  }

  provisioner "shell" {
    inline = [
      "sudo apt-get update && sudo apt-get upgrade -y",
      "sudo apt-get install -y awscli htop openjdk-11-jre python3",
      "sudo apt-get autoremove -y"
    ]
  }

  provisioner "shell" {
    script = "./install-amazon-cloudwatch-agent.sh"
  }

  provisioner "shell" {
    script = "./install-amazon-ssm-agent.sh"
  }

  provisioner "shell" {
    script = "./install-aws-cfn-bootstrap.sh"
  }

  provisioner "shell" {
    script = "./install-nix.sh"
  }

  provisioner "shell" {
    inline = [
      ". /home/admin/.nix-profile/etc/profile.d/nix.sh",
      "nix-channel --add https://nixos.org/channels/nixpkgs-21.05 nixpkgs"
    ]
  }

  provisioner "shell" {
    script = "./install-datapub.sh"
  }

  provisioner "file" {
    destination = "/tmp/"
    source      = "./amazon-cloudwatch-agent.json"
  }

  provisioner "shell" {
    inline = [
      "sudo chown root:root /tmp/amazon-cloudwatch-agent.json",
      "sudo mv /tmp/amazon-cloudwatch-agent.json /opt/aws/amazon-cloudwatch-agent/etc/"
    ]
  }

  provisioner "shell" {
    script = "./start-amazon-cloudwatch-agent.sh"
  }

  provisioner "shell" {
    script = "./enable-apt-services.sh"
  }

}
