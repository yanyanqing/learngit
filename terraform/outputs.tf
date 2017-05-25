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

# output "stability_tikv_private_ip" {
#   value = "${join(",", aws_instance.stability_tikv.*.private_ip)}"
# }

# output "stability_tidb_private_ip" {
#   value = "${join(",", aws_instance.stability_tidb.*.private_ip)}"
# }

# output "stability_pd_private_ip" {
#   value = "${join(",", aws_instance.stability_pd.*.private_ip)}"
# }

# output "binlog_pump_private_ip" {
#   value = "${join(",", aws_instance.binlog_pump.*.private_ip)}"
# }

# output "binlog_drainer_private_ip" {
#   value = "${join(",", aws_instance.binlog_drainer.*.private_ip)}"
# }

# output "region_test_tidb_private_ip" {
#   value = "${join(",", aws_instance.region_test_tidb.*.private_ip)}"
# }

# output "region_test_pd_private_ip" {
#   value = "${join(",", aws_instance.region_test_pd.*.private_ip)}"
# }

# output "region_test_tikv_private_ip" {
#   value = "${join(",", aws_instance.region_test_tikv.*.private_ip)}"
# }

# output "bench_test_private_ip" {
#   value = "${join(",", aws_instance.bench_test.*.private_ip)}"
# }

# output "jenkins_master_private_ip" {
#   value = "${join(",", aws_instance.jenkins_master.*.private_ip)}"
# }

# output "jenkins_node_private_ip" {
#   value = "${join(",", aws_instance.jenkins_node.*.private_ip)}"
# }

# output "oltp_bank_private_ip" {
#   value = "${join(",", aws_instance.oltp_bank_test.*.private_ip)}"
# }

# output "async_apply_private_ip" {
#   value = "${join(",", aws_instance.async_apply_test.*.private_ip)}"
# }
