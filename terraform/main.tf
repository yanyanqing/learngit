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


# resource "aws_security_group" "jenkins" {
#   name_prefix = "pingcap_jenkins"
#   description = "jenkins traffic"
#   vpc_id = "${var.pingcap_vpc}"
#   ingress {
#     from_port = 32375
#     to_port = 32375
#     protocol = "tcp"
#     cidr_blocks = ["${var.cidr_blocks["vpc"]}"]
#   }
# }


resource "aws_security_group" "binlog" {
  name_prefix = "pingcap_binlog"
  description = "binlog traffic"
  vpc_id = "${var.pingcap_vpc}"
  ingress {
    from_port = 8249
    to_port = 8250
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
  vpc_security_group_ids = ["${aws_security_group.base.id}", "${aws_security_group.nginx.id}"]
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

resource "aws_instance" "stability_pd" {
  ami = "${var.ami["stability_pd"]}"
  instance_type = "${var.instance_type["stability_pd"]}"
  key_name = "${var.ssh_key_name["internal"]}"
  count = "${var.count["stability_pd"]}"
  subnet_id = "${var.subnet["stability"]}"
  vpc_security_group_ids = ["${aws_security_group.base.id}", "${aws_security_group.pd.id}"]
  connection {
    user = "ubuntu"
    agent = false
    private_key = "${file(format("~/.ssh/%s.pem", var.ssh_key_name["internal"]))}"
    bastion_host = "${var.bastion_host}"
    bastion_user = "ec2-user"
    bastion_private_key = "${file(format("~/.ssh/%s.pem", var.ssh_key_name["bastion"]))}"
  }
  tags {
    Name = "stability-pd-${count.index}"
    Creator = "dengshuan"
  }
}


resource "aws_instance" "stability_tidb" {
  ami = "${var.ami["stability_tidb"]}"
  instance_type = "${var.instance_type["stability_tidb"]}"
  key_name = "${var.ssh_key_name["internal"]}"
  count = "${var.count["stability_tidb"]}"
  subnet_id = "${var.subnet["stability"]}"
  vpc_security_group_ids = ["${aws_security_group.base.id}", "${aws_security_group.tidb.id}"]
  connection {
    user = "ubuntu"
    agent = false
    private_key = "${file(format("~/.ssh/%s.pem", var.ssh_key_name["internal"]))}"
    bastion_host = "${var.bastion_host}"
    bastion_user = "ec2-user"
    bastion_private_key = "${file(format("~/.ssh/%s.pem", var.ssh_key_name["bastion"]))}"
  }
  tags {
    Name = "stability-tidb-${count.index}"
    Creator = "dengshuan"
  }
}


resource "aws_instance" "stability_tikv" {
  ami = "${var.ami["stability_tikv"]}"
  instance_type = "${var.instance_type["stability_tikv"]}"
  key_name = "${var.ssh_key_name["internal"]}"
  count = "${var.count["stability_tikv"]}"
  subnet_id = "${var.subnet["stability"]}"
  vpc_security_group_ids = ["${aws_security_group.base.id}", "${aws_security_group.tikv.id}"]
  connection {
    user = "ubuntu"
    agent = false
    private_key = "${file(format("~/.ssh/%s.pem", var.ssh_key_name["internal"]))}"
    bastion_host = "${var.bastion_host}"
    bastion_user = "ec2-user"
    bastion_private_key = "${file(format("~/.ssh/%s.pem", var.ssh_key_name["bastion"]))}"
  }
  tags {
    Name = "stability-tikv-${count.index}"
    Creator = "dengshuan"
  }
}

resource "aws_instance" "region_test_pd" {
  ami = "${var.ami["region_test_pd"]}"
  instance_type = "${var.instance_type["region_test_pd"]}"
  key_name = "${var.ssh_key_name["internal"]}"
  count = "${var.count["region_test_pd"]}"
  subnet_id = "${var.subnet["stability"]}"
  vpc_security_group_ids = ["${aws_security_group.base.id}", "${aws_security_group.pd.id}"]
  connection {
    user = "ubuntu"
    agent = false
    private_key = "${file(format("~/.ssh/%s.pem", var.ssh_key_name["internal"]))}"
    bastion_host = "${var.bastion_host}"
    bastion_user = "ec2-user"
    bastion_private_key = "${file(format("~/.ssh/%s.pem", var.ssh_key_name["bastion"]))}"
  }
  tags {
    Name = "region-test-pd-${count.index}"
    Creator = "shuning"
  }
}


resource "aws_instance" "region_test_tidb" {
  ami = "${var.ami["region_test_tidb"]}"
  instance_type = "${var.instance_type["region_test_tidb"]}"
  key_name = "${var.ssh_key_name["internal"]}"
  count = "${var.count["region_test_tidb"]}"
  subnet_id = "${var.subnet["stability"]}"
  vpc_security_group_ids = ["${aws_security_group.base.id}", "${aws_security_group.tidb.id}"]
  connection {
    user = "ubuntu"
    agent = false
    private_key = "${file(format("~/.ssh/%s.pem", var.ssh_key_name["internal"]))}"
    bastion_host = "${var.bastion_host}"
    bastion_user = "ec2-user"
    bastion_private_key = "${file(format("~/.ssh/%s.pem", var.ssh_key_name["bastion"]))}"
  }
  tags {
    Name = "region-tidb-${count.index}"
    Creator = "shuning"
  }
}


resource "aws_instance" "region_test_tikv" {
  ami = "${var.ami["region_test_tikv"]}"
  instance_type = "${var.instance_type["region_test_tikv"]}"
  key_name = "${var.ssh_key_name["internal"]}"
  count = "${var.count["region_test_tikv"]}"
  subnet_id = "${var.subnet["stability"]}"
  vpc_security_group_ids = ["${aws_security_group.base.id}", "${aws_security_group.tikv.id}"]
  connection {
    user = "ubuntu"
    agent = false
    private_key = "${file(format("~/.ssh/%s.pem", var.ssh_key_name["internal"]))}"
    bastion_host = "${var.bastion_host}"
    bastion_user = "ec2-user"
    bastion_private_key = "${file(format("~/.ssh/%s.pem", var.ssh_key_name["bastion"]))}"
  }
  tags {
    Name = "region-test-tikv-${count.index}"
    Creator = "shuning"
  }
}



# resource "aws_instance" "jenkins_master" {
#   ami = "${var.ami["jenkins_master"]}"
#   instance_type = "${var.instance_type["jenkins_master"]}"
#   key_name = "${var.ssh_key_name["internal"]}"
#   count = "${var.count["jenkins_master"]}"
#   subnet_id = "${var.subnet["jenkins"]}"
#   vpc_security_group_ids = ["${aws_security_group.base.id}", "${aws_security_group.jenkins.id}"]
#   connection {
#     user = "ubuntu"
#     agent = false
#     private_key = "${file(format("~/.ssh/%s.pem", var.ssh_key_name["internal"]))}"
#     bastion_host = "${var.bastion_host}"
#     bastion_user = "ec2-user"
#     bastion_private_key = "${file(format("~/.ssh/%s.pem", var.ssh_key_name["bastion"]))}"
#   }
#   tags {
#     Name = "jenkins-master-${count.index}"
#     Creator = "dengshuan"
#   }
# }


# resource "aws_instance" "jenkins_node" {
#   ami = "${var.ami["jenkins_node"]}"
#   instance_type = "${var.instance_type["jenkins_node"]}"
#   key_name = "${var.ssh_key_name["internal"]}"
#   count = "${var.count["jenkins_node"]}"
#   subnet_id = "${var.subnet["jenkins"]}"
#   vpc_security_group_ids = ["${aws_security_group.base.id}", "${aws_security_group.jenkins.id}"]
#   connection {
#     user = "ubuntu"
#     agent = false
#     private_key = "${file(format("~/.ssh/%s.pem", var.ssh_key_name["internal"]))}"
#     bastion_host = "${var.bastion_host}"
#     bastion_user = "ec2-user"
#     bastion_private_key = "${file(format("~/.ssh/%s.pem", var.ssh_key_name["bastion"]))}"
#   }
#   tags {
#     Name = "jenkins-node-${count.index}"
#     Creator = "dengshuan"
#   }
# }


resource "aws_instance" "binlog_pump" {
  ami = "${var.ami["binlog_pump"]}"
  instance_type = "${var.instance_type["binlog_pump"]}"
  key_name = "${var.ssh_key_name["internal"]}"
  count = "${var.count["binlog_pump"]}"
  subnet_id = "${var.subnet["binlog"]}"
  vpc_security_group_ids = ["${aws_security_group.base.id}", "${aws_security_group.binlog.id}"]
  connection {
    user = "ubuntu"
    agent = false
    private_key = "${file(format("~/.ssh/%s.pem", var.ssh_key_name["internal"]))}"
    bastion_host = "${var.bastion_host}"
    bastion_user = "ec2-user"
    bastion_private_key = "${file(format("~/.ssh/%s.pem", var.ssh_key_name["bastion"]))}"
  }
  tags {
    Name = "binlog-pump-${count.index}"
    Creator = "dengshuan"
  }
}


resource "aws_instance" "binlog_drainer" {
  ami = "${var.ami["binlog_drainer"]}"
  instance_type = "${var.instance_type["binlog_drainer"]}"
  key_name = "${var.ssh_key_name["internal"]}"
  count = "${var.count["binlog_drainer"]}"
  subnet_id = "${var.subnet["binlog"]}"
  vpc_security_group_ids = ["${aws_security_group.base.id}", "${aws_security_group.tidb.id}", "${aws_security_group.pd.id}", "${aws_security_group.tikv.id}", "${aws_security_group.binlog.id}"]
  connection {
    user = "ubuntu"
    agent = false
    private_key = "${file(format("~/.ssh/%s.pem", var.ssh_key_name["internal"]))}"
    bastion_host = "${var.bastion_host}"
    bastion_user = "ec2-user"
    bastion_private_key = "${file(format("~/.ssh/%s.pem", var.ssh_key_name["bastion"]))}"
  }
  tags {
    Name = "binlog-drainer-${count.index}"
    Creator = "dengshuan"
  }
}
