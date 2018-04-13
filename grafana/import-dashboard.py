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
cluster_name = 'TiDB Cluster'
datasource = 'tidb-cluster'
kind = ''     # if empty, import all dashboards, namely pd, tikv, tidb
##############################################


dashboards = {
    'pd': '{} - PD'.format(cluster_name),
    'tikv': '{} - TiKV'.format(cluster_name),
    'tidb': '{} - TiDB'.format(cluster_name),
}

def get_auth():
    if api_key:
        return  'Bearer {}'.format(api_key)
    elif user and password:
        basic_auth = base64.b64encode('%s:%s' % (user, password))
        return 'Basic {}'.format(basic_auth)
    else:
        return None

def import_dashboard(dashboard_file, url, auth, datasource, dashboard_name): # imports from json
    if not auth:
        print('Error: no auth provided')
        return
    with open(dashboard_file, 'r') as f:
        dashboard = json.load(f)
    dashboard['title'] = dashboard_name
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
    if 'annotations' in dashboard:
        for annotation in dashboard['annotations']['list']:
            annotation['datasource'] = '${DS_TIDB-CLUSTER}'
    inputs = [
        {
            'name': 'DS_TIDB-CLUSTER',
            'type': 'datasource',
            'pluginId': 'prometheus',
            'value': datasource,
        },
    ]
    payload = {'dashboard': dashboard, 'overwrite': True, 'inputs': inputs}
    headers = {'Authorization': auth, 'Content-Type': 'application/json'}
    api_url = '{}/api/dashboards/import'.format(url)
    req = urllib2.Request(api_url, headers=headers, data=json.dumps(payload))
    print('importing dashboard {} as {}'.format(kind, dashboard_name))
    resp = urllib2.urlopen(req)
    try:
        resp = urllib2.urlopen(req)
        data = json.load(resp)
        if data.get('importedUri'):
            print("successfully importing {} dashboard to {}/dashboard/{}".format(kind, url, data.get('importedUri')))
    except urllib2.HTTPError, error:
        data = json.load(error)
        print(error)

if __name__ == '__main__':
    auth = get_auth()
    if not kind:
        for kind, name in dashboards.items():
            dashboard_file = '{}.json'.format(kind)
            import_dashboard(dashboard_file, url, auth, datasource, name)
    else:
        dashboard_file = '{}.json'.format(kind)
        name = dashboards[kind]
        import_dashboard(dashboard_file, url, auth, datasource, name)
