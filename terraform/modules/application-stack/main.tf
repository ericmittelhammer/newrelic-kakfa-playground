data "aws_vpc" "default" {
  default = true
}

data "aws_ami" "ubuntu" {
    most_recent = true

    filter {
        name   = "name"
        values = ["ubuntu/images/hvm-ssd/ubuntu-bionic-18.04-amd64-server-*"]
    }

    filter {
        name   = "virtualization-type"
        values = ["hvm"]
    }

    owners = ["099720109477"] # Canonical
}

locals {
  vpc_id = var.vpc_id == "" ? data.aws_vpc.default.id : var.vpc_id
}

resource "aws_instance" "swarm_nodes" {
    ami = data.aws_ami.ubuntu.id
    instance_type = var.instance_type
    associate_public_ip_address = true
    key_name = var.key_name
    vpc_security_group_ids = var.security_group_ids
    
    count = var.num_nodes
    
    tags = {
      Name = "newrelic-kafka-playground-swarm-${count.index}"
      Role = "swarm"
      my_id = "${count.index}"
      Project = var.project_name
    }
}
