# -*- hcl-*-

variable pingcap_vpc {
  default = "vpc-83b54ee7"
}

variable cidr_blocks {
  default = {
    all = "0.0.0.0/0"
    vpc = "10.0.0.0/16"
    public = "10.0.0.0/24"
    stability = "10.0.1.0/24"
    jenkins = "10.0.2.0/24"
    binlog = "10.0.3.0/24"
  }
}

variable subnet {
  default = {
    public = "subnet-4af1c72f"
    stability = "subnet-4bf1c72e"
    jenkins = "subnet-2fe7d14a"
    binlog = "subnet-8dba8ce8"
  }
}

variable bastion_host {
  default = "54.223.197.155"
}

variable ssh_key_name {
  default = {
    bastion = "pingcap-public"
    internal = "pingcap-internal"
  }
}

variable ami {
  default = {
    nginx = "ami-0220b23b" # ubuntu-14.04
    prometheus = "ami-28469245"	# from previous ami
    stability_pd = "ami-fd528690"
    stability_tidb = "ami-fd528690"
    stability_tikv = "ami-fd528690"
    jenkins_master = "ami-fd52869"
    jenkins_node = "ami-fd52869"
    binlog_pump = "ami-0220b23b"
    binlog_drainer = "ami-0220b23b"
  }
}

variable instance_type {
  default = {
    nginx = "t2.small"
    prometheus = "t2.xlarge"
    stability_pd = "t2.micro"
    stability_tidb = "t2.micro"
    stability_tikv = "t2.micro"
    jenkins_master = "t2.micro"
    jenkins_node = "t2.micro"
    binlog_pump = "m3.medium"
    binlog_drainer = "m3.large"
  }
}

variable count {
  default = {
    nginx = 1
    prometheus = 1
    stability_pd = 3
    stability_tidb = 1
    stability_tikv = 15
    jenkins_master = 1
    jenkins_node = 4
    binlog_pump = 10
    binlog_drainer = 1
  }
}
