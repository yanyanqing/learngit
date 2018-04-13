#!/usr/bin/env python2
import urllib2
import json

dashboard_url = 'https://grafana.com/api/dashboards'
# This is dashboard ids of pd,tikv,tidb on https://grafana.com/users/tennix
dashboards = {
    'pd': 1489,
    'tidb': 1495,
    'tikv': 1492,
}

def download_dashboard():
    for kind, id in dashboards.items():
        rev_url = '{}/{}/revisions'.format(dashboard_url, id)
        req = urllib2.Request(rev_url)
        resp = urllib2.urlopen(req)
        data = json.load(resp)
        rev_id = 1
        for rev in data['items']:
            if rev['revision'] > rev_id:
                rev_id = rev['revision']
        download_url = '{}/{}/revisions/{}/download'.format(dashboard_url, id, rev_id)
        # tikv dashboard would be truncated if downloaded by curl/wget or python urllib2
        if kind == 'tikv':
            print('### please download tikv dashboard {} from your web browser ###'.format(download_url))
            continue
        print('download {} dashboard from {}'.format(kind, download_url))
        req = urllib2.Request(download_url)
        resp = urllib2.urlopen(req)
        dashboard = json.load(resp)
        dashboard_file = '{}.json'.format(kind)
        with open(dashboard_file, 'w') as f:
            json.dump(dashboard, f, indent=2)

if __name__ == '__main__':
    download_dashboard()
