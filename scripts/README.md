# Grafana Dashboards Copy Script

## Usage

### Prepare two configuration files

* src.json

	```json
	{
		"name": "My Cluster1",
		"url": "http://localhost:3000/grafana/",
		"api_key": "",
		"user": "admin",
		"password": "admin",
		"datasource": "My Cluster1",
		"dashboards": {
			"pd": "My-Cluster1-PD",
			"tidb": "My-Cluster1-TiDB",
			"tikv": "My-Cluster1-TiKV"
		}
	}
	```
	You can either provide a viewer api key, or user and password. `datasource` can be empty. And you can also export other dashboards as long as it exists.


* dst.json

	```json
	{
		"name": "My Cluster2",
		"url": "http://localhost:3000/grafana/",
		"api_key": "",
		"user": "admin",
		"password": "admin",
		"datasource": "My Cluster2",
		"dashboards": {
			"pd": "My-Cluster2-PD",
			"tidb": "My-Cluster2-TiDB",
			"tikv": "My-Cluster2-TiKV"
		}
	}
	```
	You can either provide an editor api key, or user and password. If `url` is empty, json files will be dumped to current directory.

### Run `./grafana-config-copy.py src.json dst.json`


# Prometheus & Pushgateway metrics cleaner script

When metrics is behind pushgateway, prometheus could not determine whether a server is down or not, so would continue retrieving metrics from pushgateway cached data which are the last pushed metrics. These metrics would cause confusion when viewing dashboards and fire alerts to alertmanager continually. So these outdated metrics must be cleaned up. metrics-delete.py script does the job.

```
export PROMETHEUS_URL=http://127.0.0.1:9090
export PUSHGATEWAY_URL=http://127.0.0.1:9091
export TIMEOUT=5
./metrics-delete.py
```

`TIMEOUT` environment variable is how long a metrics can be seen as outdated if not updating, its unit is minutes. Default timeout is 5 minutes. Be careful when setting this too small, because it will delete the outdated metrics from prometheus permanently.
