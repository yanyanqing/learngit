#!/usr/bin/python
#-*- coding:utf-8 -*-
############################
#File Name: upload.py
#Author: shenli
#Mail: shenli3514@gmail.com
#Created Time: 2016-10-12 01:22:56
############################

import sys
from qiniu import Auth, put_file, etag, urlsafe_base64_encode
import qiniu.config

#需要填写你的 Access Key 和 Secret Key
access_key = 'slRwjKN1IDdNJ_wqdj54HtXicTLZXDcNoxEti5Lk'
secret_key = 'dunGNv2RA5dpoqDVHVQ9srVIdkv6K3AtQHWTwpS3'

# local_file: local file path
# remote_name: 上传到七牛后保存的文件名
def upload(local_file, remote_name, ttl=3600):
    print local_file, remote_name, ttl
    #构建鉴权对象
    q = Auth(access_key, secret_key)

    #要上传的空间
    bucket_name = 'tidb'

    #生成上传 Token，可以指定过期时间等
    token = q.upload_token(bucket_name, remote_name, ttl)

    ret, info = put_file(token, remote_name, local_file)
    print(info)
    assert ret['key'] == remote_name
    assert ret['hash'] == etag(local_file)

if __name__ == "__main__":
    local_file = sys.argv[1]
    remote_name = sys.argv[2]
    upload(local_file, remote_name)
