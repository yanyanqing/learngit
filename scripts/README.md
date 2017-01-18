# Grafana Dashboards Copy Scripts

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
