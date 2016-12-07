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


output "stability_tikv_private_ip" {
  value = "${join(",", aws_instance.stability_tikv.*.private_ip)}"
}


output "stability_tidb_private_ip" {
  value = "${join(",", aws_instance.stability_tidb.*.private_ip)}"
}


output "stability_pd_private_ip" {
  value = "${join(",", aws_instance.stability_pd.*.private_ip)}"
}


output "binlog_pump_private_ip" {
  value = "${join(",", aws_instance.binlog_pump.*.private_ip)}"
}


output "binlog_drainer_private_ip" {
  value = "${join(",", aws_instance.binlog_drainer.*.private_ip)}"
}


output "region_test_tidb_private_ip" {
  value = "${join(",", aws_instance.region_test_tidb.*.private_ip)}"
}


output "region_test_pd_private_ip" {
  value = "${join(",", aws_instance.region_test_pd.*.private_ip)}"
}


output "region_test_tikv_private_ip" {
  value = "${join(",", aws_instance.region_test_tikv.*.private_ip)}"
}
