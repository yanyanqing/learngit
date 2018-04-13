#!/usr/bin/env python2

from __future__ import print_function, \
    unicode_literals
import sys
import urllib2
import base64
import json

class Cluster(object):
    def __init__(self, name, url, datasource, dashboards, template=True):
        self.name = name
        self.url = url
        self.datasource = datasource
        self.dashboards = dashboards
        self.template = template

    def set_auth(self, api_key, user, password):
        if api_key:
            self.auth = 'Bearer {}'.format(api_key)
        elif user and password:
            basic_auth = base64.b64encode('%s:%s' % (user, password))
            self.auth = 'Basic {}'.format(basic_auth)
        else:
            self.auth = None

    def exports(self):
        if not self.auth:
            print('Error: no auth provided')
            return None
        dashboards = {}
        for kind, dashboard in self.dashboards.items():
            print('Exporting {} dashboard from {} of cluster {}'.format(kind, dashboard, self.name), end='\t...... ')
            api_url = self.url + 'api/dashboards/db/' + dashboard
            req = urllib2.Request(api_url, headers={'Authorization': self.auth, 'Content-Type': 'application/json'})
            resp = urllib2.urlopen(req) # TODO: add handler when no such dashboard in template
            data = json.load(resp)
            dashboards[kind] = data
            print('done')
        return dashboards

    def dumps(self, datasource, cluster='TiDB Cluster'):
        dashboards = self.exports()
        for kind, dashboard in dashboards.items():
            print('Dumping {} dashboard as {}.json'.format(kind, kind), end='\t...... ')
            dashboard = dashboard['dashboard']
            dashboard['title'] = '{} - {}'.format(cluster, kind)
            dashboard['id'] = None
            for row in dashboard['rows']:
                for panel in row['panels']:
                    panel['datasource'] = '${DS_TiDB-Cluster}'
            if 'templating' in dashboard:
                for templating in dashboard['templating']['list']:
                    if templating['type'] == 'query':
                        templating['current'] = {}
                        templating['options'] = []
                    templating['datasource'] = self.datasource
            if 'annotations' in dashboard:
                for annotation in dashboard['annotations']['list']:
                    annotation['datasource'] = self.datasource
            dashboard['__inputs'] = [
                {'name': 'DS_TiDB-Cluster', 'label': cluster, 'description': '', 'type': 'datasource', 'pluginId': 'prometheus', 'pluginName': 'Prometheus'}
            ]
            with open('{}.json'.format(kind), 'w') as f:
                json.dump(dashboard, f, indent=2)
                print('done')

    def imports(self, dashboards):
        if not self.auth:
            print('Error: no auth provided')
            return
        if self.template:   # forbid importing to template grafana
            print('Error: cannot import dashboards to template cluster')
            return
        for kind, title in self.dashboards.items():
            if not dashboards[kind]:
                print('Warning: no dashboard for {} found in template cluster, skipping'.format(kind))
                continue
            print('Importing {} dashboard as {} to {}'.format(kind, title, self.name), end='\t...... ')
            dashboard = dashboards[kind]['dashboard']
            dashboard['title'] = title
            dashboard['id'] = None
            for row in dashboard['rows']:
                for panel in row['panels']:
                    panel['datasource'] = self.datasource
            if 'templating' in dashboard:
                for templating in dashboard['templating']['list']:
                    if templating['type'] == 'query':
                        templating['current'] = {}
                        templating['options'] = []
                    templating['datasource'] = self.datasource
            if 'annotations' in dashboard:
                for annotation in dashboard['annotations']['list']:
                    annotation['datasource'] = self.datasource
            payload = {'dashboard': dashboard, 'overwrite': True}
            headers = {'Authorization': self.auth, 'Content-Type': 'application/json'}
            api_url = self.url + 'api/dashboards/db'
            req = urllib2.Request(api_url, headers=headers, data=json.dumps(payload))
            resp = urllib2.urlopen(req)
            try:
                resp = urllib2.urlopen(req)
                data = json.load(resp)
                print(data['status'])
            except urllib2.HTTPError, error:
                data = json.load(error)
                print(error)

if __name__ == '__main__':
    if len(sys.argv) != 3:
        print('Usage: {} src.json dst.json'.format(sys.argv[0]))
        exit()
    with open(sys.argv[1], 'r') as f:
        src = json.load(f)
    c1 = Cluster(name=src['name'], url=src['url'], datasource=src['datasource'], dashboards=src['dashboards'], template=True)
    c1.set_auth(api_key=src['api_key'], user=src['user'], password=src['password'])

    with open(sys.argv[2], 'r') as f:
        dst = json.load(f)
    if not dst['url']:
        # dump dashboards as json templates
        c1.dumps(datasource=dst['datasource'], cluster=dst['name'])
        exit()
    # import to cluster2
    c2 = Cluster(name=dst['name'], url=dst['url'], datasource=dst['datasource'], dashboards=dst['dashboards'], template=False)
    c2.set_auth(api_key=dst['api_key'], user=dst['user'], password=dst['password'])
    c2.imports(c1.exports())
