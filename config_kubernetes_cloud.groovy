import org.csanchez.jenkins.plugins.kubernetes.*
import jenkins.model.*
import com.cloudbees.plugins.credentials.CredentialsScope
import com.cloudbees.plugins.credentials.SystemCredentialsProvider
import com.cloudbees.plugins.credentials.domains.Domain
import hudson.util.Secret
import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl

def jenkins = Jenkins.getInstance()
def environmentalVariables = System.getenv()
String jenkins_user_secret = environmentalVariables["JENKINS_USER_SECRET"]
String jenkinsnode_image = environmentalVariables["JENKINSNODE_IMAGE"]

// obtain jenkins private ip address

def localIp = InetAddress.localHost.canonicalHostName

def addCredential(String credentials_id, def credential) {
    boolean modified_creds = false
    Domain domain
    SystemCredentialsProvider system_creds = SystemCredentialsProvider.getInstance()
    Map system_creds_map = system_creds.getDomainCredentialsMap()
    domain = (system_creds_map.keySet() as List).find { it.getName() == null }
    if(!system_creds_map[domain] || (system_creds_map[domain].findAll {credentials_id.equals(it.id)}).size() < 1) {
        if(system_creds_map[domain] && system_creds_map[domain].size() > 0) {
            //other credentials exist so should only append
            system_creds_map[domain] << credential
        }
        else {
            system_creds_map[domain] = [credential]
        }
        modified_creds = true
    }
    //save any modified credentials
    if(modified_creds) {
        println "${credentials_id} credentials added to Jenkins."
        system_creds.setDomainCredentialsMap(system_creds_map)
        system_creds.save()
    }
    else {
        println "Nothing changed.  ${credentials_id} credentials already configured."
    }
}

def setStringCredentialsImpl(String credentials_id, String description, String secret) {
    addCredential(
            credentials_id,
            new StringCredentialsImpl(
                CredentialsScope.GLOBAL,
                credentials_id,
                description,
                Secret.fromString(secret))
            )
}

// jenkins kubernetes plugin configuration

setStringCredentialsImpl('kubernetes-admin', '', jenkins_user_secret)

List<ContainerTemplate> containerTemplates = [
  new ContainerTemplate('jnlp', jenkinsnode_image, 'jenkins-slave', '')
]
def podTemplate = new PodTemplate('jenkins-worker', containerTemplates)
podTemplate.setName('jenkins-worker')
podTemplate.setNamespace('default')
podTemplate.setLabel('jenkins-worker')

List<PodTemplate> podTemplates = [
  podTemplate
]
def kubernetesCloud = new KubernetesCloud(
  'kubernetes',
  podTemplates,
  'https://kubernetes',
  'default',
  "http://${localIp}:8080".toString(),
  '10', 60, 0, 5
)
kubernetesCloud.setSkipTlsVerify(true)
kubernetesCloud.setCredentialsId('kubernetes-admin')

jenkins.clouds.replace(kubernetesCloud)
jenkins.save()
