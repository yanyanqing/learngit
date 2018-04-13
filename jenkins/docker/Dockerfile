FROM centos:7

MAINTAINER Liu Yin <liuy@pingcap.com>

RUN yum install -y epel-release

COPY epel-apache-maven.repo /etc/yum.repos.d/epel-apache-maven.repo

RUN curl --silent --location https://rpm.nodesource.com/setup_8.x | bash -

RUN yum makecache \
	&& yum update -y \
	&& yum install -y tar wget git which file unzip python-pip \
	&& yum install -y make gcc gcc-c++ libstdc++-static pkg-config psmisc \
	&& yum install -y libzstd-devel lz4-devel zlib-devel bzip2-devel snappy-devel libdwarf-devel elfutils-libelf-devel elfutils-devel binutils-devel \
	&& yum install -y openssh-server openssl-devel \
	&& yum install -y java-1.8.0-openjdk \
	&& yum install -y nodejs \
	&& yum install -y apache-maven \
	&& yum install -y mysql \
	&& yum clean all

RUN curl -L https://cmake.org/files/v3.10/cmake-3.10.0.tar.gz -o cmake-3.10.0.tar.gz \
	&& tar xzf cmake-3.10.0.tar.gz \
	&& cd cmake-3.10.0 \
	&& ./bootstrap \
	&& make \
	&& make install \
	&& cmake --version \
	&& rm -rf /cmake-3.10.0.tar.gz /cmake-3.10.0

RUN curl -L https://github.com/gflags/gflags/archive/v2.1.2.tar.gz -o gflags.tar.gz \
	&& tar xf gflags.tar.gz \
	&& cd gflags-2.1.2 \
	&& cmake . \
	&& make -j \
	&& make install \
	&& rm -rf /gflags.tar.gz /gflags-2.1.2

#RUN curl -L https://github.com/facebook/rocksdb/archive/v5.7.3.tar.gz -o rocksdb.tar.gz \
#	&& tar xf rocksdb.tar.gz \
#	&& cd rocksdb-5.7.3 \
#	&& make -j 2 install-shared \
#	&& rm -rf /rocksdb.tar.gz /rocksdb-5.7.3

ENV GOLANG_VERSION 1.9.2
ENV GOLANG_DOWNLOAD_URL https://golang.org/dl/go$GOLANG_VERSION.linux-amd64.tar.gz
ENV GOLANG_DOWNLOAD_SHA256 de874549d9a8d8d8062be05808509c09a88a248e77ec14eb77453530829ac02b

RUN curl -fsSL "$GOLANG_DOWNLOAD_URL" -o golang.tar.gz \
	&& echo "$GOLANG_DOWNLOAD_SHA256  golang.tar.gz" | sha256sum -c - \
	&& tar -C /usr/local -xzf golang.tar.gz \
	&& rm golang.tar.gz

ENV GOPATH /go
ENV GOROOT /usr/local/go
ENV PATH $GOPATH/bin:$GOROOT/bin:$PATH

RUN go get golang.org/x/tools/go/gcimporter15 \
	&& go get github.com/golang/lint/golint

RUN curl -fsSL https://get.docker.com/ | sh || true

RUN adduser jenkins --uid 1000 \
	&& echo "jenkins:jenkins" | chpasswd \
	&& usermod -aG docker jenkins \
	&& usermod -aG input jenkins

RUN su jenkins -c "curl https://sh.rustup.rs -sSf | sh -s -- --no-modify-path --default-toolchain nightly-2017-08-08 -y" \
	&& su jenkins -c "/home/jenkins/.cargo/bin/rustup default nightly-2017-08-08"

# cache dep package
RUN su jenkins -c "curl -L https://github.com/pingcap/tikv/archive/master.tar.gz -o /home/jenkins/master.tar.gz" \
	&& su jenkins -c "curl -L http://download.pingcap.org/rustfmt-v0.6.0-linux-amd64.tar.gz -o /home/jenkins/rustfmt-v0.6.0-linux-amd64.tar.gz" \
	&& su jenkins -c "cd /home/jenkins && tar xf master.tar.gz && cd tikv-master && /home/jenkins/.cargo/bin/cargo fetch" \
	&& su jenkins -c "cd /home/jenkins && tar xzf rustfmt-v0.6.0-linux-amd64.tar.gz -C /home/jenkins/.cargo/bin --strip-components=1" \
	&& su jenkins -c "cd /home/jenkins && rm -rf master.tar.gz tikv-master rustfmt-v0.6.0-linux-amd64.tar.gz"

RUN sed -i 's|session    required     pam_loginuid.so|session    optional     pam_loginuid.so|g' /etc/pam.d/sshd \
	&& mkdir -p /var/run/sshd \
	&& ssh-keygen -t rsa -b 2048 -f /etc/ssh/ssh_host_rsa_key -N "" -q \
	&& ssh-keygen -t dsa -b 1024 -f /etc/ssh/ssh_host_dsa_key -N "" -q \
	&& ssh-keygen -t ecdsa -b 256 -f /etc/ssh/ssh_host_ecdsa_key -N "" -q \
	&& ssh-keygen -t ed25519 -b 2048 -f /etc/ssh/ssh_host_ed25519_key -N "" -q

RUN mkdir -p /home/pingcap && cd /home/pingcap \
	&& curl -fsSL "http://pingcap-dev.ufile.ucloud.com.cn/jenkins/jenkins-slave-docker-sqllogictest.tar.gz" -o jenkins-slave-docker-sqllogictest.tar.gz \
	&& tar xzvf jenkins-slave-docker-sqllogictest.tar.gz \
	&& rm -rf jenkins-slave-docker-sqllogictest.tar.gz 

RUN cd /home/jenkins \
	&& curl -fsSL "http://pingcap-dev.ufile.ucloud.com.cn/jenkins/jenkins-slave-docker-m2.tar.gz" -o jenkins-slave-docker-m2.tar.gz \
	&& tar xzvf jenkins-slave-docker-m2.tar.gz \
	&& rm -rf jenkins-slave-docker-m2.tar.gz

COPY config.json /home/jenkins/.docker/config.json

COPY .gitcookies /home/jenkins/.gitcookies

COPY bin /home/jenkins/bin

RUN pip install --upgrade pip \
	&& pip install qiniu requests

RUN chown -R jenkins:jenkins /home/jenkins/.docker \
	&& chown jenkins:jenkins /home/jenkins/.gitcookies \
	&& chmod 0600 /home/jenkins/.gitcookies \
	&& su jenkins -c "git config --global http.cookiefile /home/jenkins/.gitcookies" \
	&& chown -R jenkins:jenkins /home/jenkins/bin

EXPOSE 22

CMD ["/usr/sbin/sshd", "-D"]

