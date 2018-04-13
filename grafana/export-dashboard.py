#!/usr/bin/env python2

from __future__ import print_function, \
    unicode_literals
import urllib2
import json
import base64

############### configuration ################
url = 'http://127.0.0.1:3000'
api_key = ''         # either api_key or user & password must provided
user = 'admin'
password = 'admin'
kind = ''     # if empty, export all dashboards, namely pd, tikv, tidb
dashboards = {              # this is dashboard slug name from grafana
    'pd': 'tidb-cluster-pd',
    'tikv': 'tidb-cluster-tikv',
    'tidb': 'tidb-cluster-tidb',
}
##############################################


def get_auth():
    if api_key:
        return  'Bearer {}'.format(api_key)
    elif user and password:
        basic_auth = base64.b64encode('%s:%s' % (user, password))
        return 'Basic {}'.format(basic_auth)
    else:
        return None

def export_dashboard(url, auth, dashboard_name, kind, dashboard_file):
    if not auth:
        print('Error: no auth provided')
        return None
    api_url = '{}/api/dashboards/db/{}'.format(url, dashboard_name)
    req = urllib2.Request(api_url, headers={'Authorization': auth, 'Content-Type': 'application/json'})
    print('exporting dashboard {} from {}'.format(kind, api_url))
    resp = urllib2.urlopen(req)
    data = json.load(resp)
    dashboard = data['dashboard']
    dashboard['title'] = 'TiDB Cluster - {}'.format(kind)
    dashboard['id'] = None
    for row in dashboard['rows']:
        for panel in row['panels']:
            panel['datasource'] = '${DS_TIDB-CLUSTER}'
    if 'templating' in dashboard:
        for templating in dashboard['templating']['list']:
            if templating['type'] == 'query':
                templating['current'] = {}
                templating['options'] = []
            templating['datasource'] = '${DS_TIDB-CLUSTER}'
    if '__requires' not in dashboard:
        dashboard['__requires'] = [
            {'type': 'grafana', 'id': 'grafana', 'name': 'Grafana', 'version': '4.0.1'},
            {'type': 'datasource', 'id': 'prometheus', 'name': 'Prometheus', 'version': '1.0.0'},
        ]
    if 'annotations' in dashboard:
        for annotation in dashboard['annotations']['list']:
            annotation['datasource'] = '${DS_TIDB-CLUSTER}'
    dashboard['__inputs'] = [
        {'name': 'DS_TIDB-CLUSTER', 'label': 'tidb-cluster', 'description': '', 'type': 'datasource', 'pluginId': 'prometheus', 'pluginName': 'Prometheus'}
    ]
    with open(dashboard_file, 'w') as f:
        json.dump(dashboard, f, indent=2)
        print('done')

if __name__ == '__main__':
    auth = get_auth()
    if not kind:
        for kind, name in dashboards.items():
            dashboard_file = '{}.json'.format(kind)
            export_dashboard(url, auth, name, kind, dashboard_file)
    else:
        dashboard_file = '{}.json'.format(kind)
        name = dashboards[kind]
        export_dashboard(url, auth, name, kind, dashboard_file)
