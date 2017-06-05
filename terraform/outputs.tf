# -*- hcl -*-

output "nginx_eip" {
  value = "${aws_eip.nginx.public_ip}"
}

output "nginx_private_ip" {
  value = "${join(",", aws_instance.nginx.*.private_ip)}"
}

output "prometheus_private_ip" {
  value = "${join(",", aws_instance.prometheus.*.private_ip)}"
}

