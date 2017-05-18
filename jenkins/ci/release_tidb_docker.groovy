def call(HOSTIP, RELEASE_TAG) {

    dir('tidb_docker_build') {
        sh  """
        cp ../centos7/bin/tidb-server ./
        cat > Dockerfile << __EOF__
FROM pingcap/alpine-glibc
COPY tidb-server /tidb-server
EXPOSE 4000
ENTRYPOINT ["/tidb-server"]
__EOF__
        """
    }

    withDockerServer([uri: "tcp://${HOSTIP}:32376"]) {
        docker.build("pingcap/tidb:${RELEASE_TAG}", "tidb_docker_build").push()
    }
}

return this
