from jenkins/jenkins

# Distributed Builds plugins
RUN /usr/local/bin/install-plugins.sh ssh-slaves
RUN /usr/local/bin/install-plugins.sh kubernetes
RUN /usr/local/bin/install-plugins.sh locale
RUN /usr/local/bin/install-plugins.sh blueocean




COPY config_kubernetes_cloud.groovy /usr/share/jenkins/ref/init.groovy.d/config_kubernetes_cloud.groovy

USER root
ADD https://storage.googleapis.com/kubernetes-release/release/v1.10.0/bin/linux/amd64/kubectl /usr/local/bin/kubectl
RUN chmod +x /usr/local/bin/kubectl

USER jenkins
