# -*- hcl-*-

provider "aws" {
  region = "cn-north-1"
}

resource "aws_security_group" "base" {
  name_prefix = "pingcap_base"
  description = "self traffic and outbound"
  vpc_id = "${var.pingcap_vpc}"
  # allow all self traffic
  ingress {
    from_port = 0
    to_port = 65535
    protocol = "tcp"
    self = true
  }
  # allow all ingress traffic from default VPC
  ingress {
    from_port = 0
    to_port = 0
    protocol = "-1"
    cidr_blocks = ["${var.cidr_blocks["vpc"]}"]
  }
  # allow ssh traffic from office IP
  ingress {
    from_port = 22
    to_port = 22
    protocol = "tcp"
    cidr_blocks = ["${var.cidr_blocks["vpc"]}"]
  }
  # allow all outbound traffic
  egress {
    from_port = 0
    to_port = 0
    protocol = "-1"
    cidr_blocks = ["${var.cidr_blocks["all"]}"]
  }
}

resource "aws_security_group" "nginx" {
  name_prefix = "pingcap_nginx"
  description = "Main site nginx traffic"
  vpc_id = "${var.pingcap_vpc}"
  ingress {
    from_port = 8
    to_port = 0
    protocol = "icmp"
    cidr_blocks = ["${var.cidr_blocks["all"]}"]
  }
  ingress {
    from_port = 80
    to_port = 80
    protocol = "tcp"
    cidr_blocks = ["${var.cidr_blocks["all"]}"]
  }
  ingress {
    from_port = 443
    to_port = 443
    protocol = "tcp"
    cidr_blocks = ["${var.cidr_blocks["all"]}"]
  }
  ingress {
    from_port = 8000
    to_port = 8000
    protocol = "tcp"
    cidr_blocks = ["${var.cidr_blocks["all"]}"]
  }
  ingress {
    from_port = 8081
    to_port = 8081
    protocol = "tcp"
    cidr_blocks = ["${var.cidr_blocks["office"]}"]
  }
  ingress {
    from_port = 9093
    to_port = 9093
    protocol = "tcp"
    cidr_blocks = ["${var.cidr_blocks["all"]}"]
  }
}

resource "aws_security_group" "proxy" {
  name_prefix = "pingcap_proxy"
  description = "proxy traffic"
  vpc_id = "${var.pingcap_vpc}"
  ingress {
    from_port = 8123
    to_port = 8123
    protocol = "tcp"
    cidr_blocks = ["${var.cidr_blocks["office"]}"]
  }
  ingress {
    from_port = 8233
    to_port = 8233
    protocol = "tcp"
    cidr_blocks = ["${var.cidr_blocks["office"]}"]
  }
  ingress {
    from_port = 1111
    to_port = 1111
    protocol = "tcp"
    cidr_blocks = ["${var.cidr_blocks["all"]}"]
  }
}

resource "aws_security_group" "prometheus" {
  name_prefix = "pingcap_prometheus"
  description = "prometheus traffic"
  vpc_id = "${var.pingcap_vpc}"
  ingress {
    from_port = 9090
    to_port = 9093
    protocol = "tcp"
    cidr_blocks = ["${var.cidr_blocks["vpc"]}"]
  }
}

resource "aws_security_group" "grafana" {
  name_prefix = "pingcap_grafana"
  description = "grafana traffic"
  vpc_id = "${var.pingcap_vpc}"
  ingress {
    from_port = 3000
    to_port = 3000
    protocol = "tcp"
    cidr_blocks = ["${var.cidr_blocks["vpc"]}"]
  }
}

resource "aws_security_group" "tidb" {
  name_prefix = "pingcap_tidb"
  description = "tidb traffic"
  vpc_id = "${var.pingcap_vpc}"
  ingress {
    from_port = 4000
    to_port = 4000
    protocol = "tcp"
    cidr_blocks = ["${var.cidr_blocks["vpc"]}"]
  }
}

resource "aws_security_group" "pd" {
  name_prefix = "pingcap_pd"
  description = "pd traffic"
  vpc_id = "${var.pingcap_vpc}"
  ingress {
    from_port = 2379
    to_port = 2380
    protocol = "tcp"
    cidr_blocks = ["${var.cidr_blocks["vpc"]}"]
  }
}

resource "aws_security_group" "tikv" {
  name_prefix = "pingcap_tikv"
  description = "tikv traffic"
  vpc_id = "${var.pingcap_vpc}"
  ingress {
    from_port = 20160
    to_port = 20160
    protocol = "tcp"
    cidr_blocks = ["${var.cidr_blocks["vpc"]}"]
  }
}

resource "aws_instance" "nginx" {
  ami = "${var.ami["nginx"]}"
  instance_type = "${var.instance_type["nginx"]}"
  key_name = "${var.ssh_key_name["internal"]}"
  count = "${var.count["nginx"]}"
  subnet_id = "${var.subnet["public"]}"
  vpc_security_group_ids = ["${aws_security_group.base.id}", "${aws_security_group.nginx.id}", "${aws_security_group.proxy.id}"]
  private_ip = "10.0.0.10"
  connection {
    user = "ubuntu"
    agent = false
    private_key = "${file(format("~/.ssh/%s.pem", var.ssh_key_name["internal"]))}"
    bastion_host = "${var.bastion_host}"
    bastion_user = "ec2-user"
    bastion_private_key = "${file(format("~/.ssh/%s.pem", var.ssh_key_name["bastion"]))}"
  }
  tags {
    Name = "NGINX-${count.index}"
    Creator = "dengshuan"
  }
}

resource "aws_eip" "nginx" {
  vpc = true
  instance = "${aws_instance.nginx.id}"
  associate_with_private_ip = "10.0.0.10"
}

resource "aws_ebs_volume" "prometheus" {
  availability_zone = "cn-north-1a"
  size = 100
  tags {
    Name = "Prometheus"
  }
}

resource "aws_volume_attachment" "ebs_att" {
  device_name = "/dev/xvdb"
  volume_id = "${aws_ebs_volume.prometheus.id}"
  instance_id = "${aws_instance.prometheus.id}"
}

resource "aws_instance" "prometheus" {
  ami = "${var.ami["prometheus"]}"
  instance_type = "${var.instance_type["prometheus"]}"
  key_name = "${var.ssh_key_name["internal"]}"
  count = "${var.count["prometheus"]}"
  subnet_id = "${var.subnet["stability"]}"
  vpc_security_group_ids = ["${aws_security_group.base.id}", "${aws_security_group.prometheus.id}"]
  private_ip = "10.0.1.10"
  connection {
    user = "ubuntu"
    agent = false
    private_key = "${file(format("~/.ssh/%s.pem", var.ssh_key_name["internal"]))}"
    bastion_host = "${var.bastion_host}"
    bastion_user = "ec2-user"
    bastion_private_key = "${file(format("~/.ssh/%s.pem", var.ssh_key_name["bastion"]))}"
  }
  tags {
    Name = "PROMETHEUS-${count.index}"
    Creator = "dengshuan"
  }
}
