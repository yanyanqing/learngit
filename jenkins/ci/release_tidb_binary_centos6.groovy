def call(RELEASE_TAG) {
    def target = "tidb-${RELEASE_TAG}-linux-amd64-centos6"

    dir("${target}") {
        sh "cp -R ../centos7/bin ./"
        sh "cp ../centos6/bin/tikv-server bin/"
    }

    sh """
    tar czvf ${target}.tar.gz ${target}
    sha256sum ${target}.tar.gz > ${target}.sha256
    md5sum ${target}.tar.gz > ${target}.md5
    """

    sh """
    upload.py ${target}.tar.gz ${target}.tar.gz
    upload.py ${target}.sha256 ${target}.sha256
    upload.py ${target}.md5 ${target}.md5
    """
}

return this
