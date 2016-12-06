# AWS terraform infra

This is our AWS infrastructure, all our long term instances on AWS are created by this terraform configs. Currently includes:

* Nginx: 1 t2.small
* Prometheus: 1 t2.xlarge
* Stability test: pd(3 t2.micro) + tidb(1 t2.micro) + tikv(15 t2.micro)
* Binlog stability test: pump(10 m3.medium) + drainer(1 m3.large)

With [graphviz](http://www.graphviz.or) installed, we can generate a graph of all AWS resources by `terraform graph | dot -Tpng > aws.png`


*Note*: This is our online services, please **never never never** run `terraform destroy` in this directory. And before running `terraform apply`, running `terraform plan` to see what resources will be added/changed/destroyed is a good habit. This will help to prevent you from doing stupid things.
