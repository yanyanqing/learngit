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
datasource = {
    "name": "tidb-cluster",
    "type": "prometheus",
    "url": "http://127.0.0.1:9090",
    "access": "proxy",          # proxy | direct
    "basicAuth": False
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

def add_datasource(url, auth, datasource):
    if not auth:
        print('Error: no auth provided')
        return None
    api_url = '{}/api/datasources'.format(url)
    headers = {'Authorization': auth, 'Content-Type': 'application/json'}
    req = urllib2.Request(api_url, headers=headers, data=json.dumps(datasource))
    print('adding datasource {} to {}'.format(datasource['name'], api_url))
    resp = urllib2.urlopen(req)
    data = json.load(resp)
    print('datasource {} added id {}'.format(datasource['name'], data['id']))


if __name__ == '__main__':
    auth = get_auth()
    add_datasource(url, auth, datasource)
