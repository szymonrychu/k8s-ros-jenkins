import org.csanchez.jenkins.plugins.kubernetes.*
import jenkins.model.*
import com.cloudbees.plugins.credentials.CredentialsScope
import com.cloudbees.plugins.credentials.SystemCredentialsProvider
import com.cloudbees.plugins.credentials.domains.Domain
import hudson.util.Secret
import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl
import hudson.security.*

// functions
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
// get master objects
def jenkins = Jenkins.getInstance()
def environmentalVariables = System.getenv()
def localIp = InetAddress.localHost.canonicalHostName
// set variables based on env
String jenkins_admin_username = "admin"
String jenkins_admin_password = "*Passw0rd123!"
String jenkins_k8s_secret = "Password123!"
String jenkinsnode_image = "jenkinsci/jnlp-slave"
if(environmentalVariables.containsKey("JENKINS_USER_SECRET")){
  jenkins_k8s_secret = environmentalVariables["JENKINS_USER_SECRET"]
}
if(environmentalVariables.containsKey("JENKINSNODE_IMAGE")){
  jenkinsnode_image = environmentalVariables["JENKINSNODE_IMAGE"]
}
if(environmentalVariables.containsKey("JENKINS_ADMIN_USERNAME")){
  jenkins_admin_username = environmentalVariables["JENKINS_ADMIN_USERNAME"]
}
if(environmentalVariables.containsKey("JENKINS_ADMIN_PASSWORD")){
  jenkins_admin_password = environmentalVariables["JENKINS_ADMIN_PASSWORD"]
}
// prepare master admin user
def hudsonRealm = new HudsonPrivateSecurityRealm(false)
hudsonRealm.createAccount(jenkins_admin_username, jenkins_admin_password)
jenkins.setSecurityRealm(hudsonRealm)
def strategy = new GlobalMatrixAuthorizationStrategy()
strategy.add(Jenkins.ADMINISTER, jenkins_admin_username)
jenkins.setAuthorizationStrategy(strategy)
user = hudson.model.User.get(jenkins_admin_username)
prop = user.getProperty(jenkins.security.ApiTokenProperty.class)
// prepare jenkins credentials with api-key
setStringCredentialsImpl('kubernetes-admin', '', jenkins_k8s_secret)
// prepare kubernetes cloud
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
  "http://jenkins:8080".toString(),
  '10', 60, 0, 5
)
kubernetesCloud.setSkipTlsVerify(true)
kubernetesCloud.setCredentialsId('kubernetes-admin')
jenkins.clouds.replace(kubernetesCloud)
// set jenkins number od executors
jenkins.setNumExecutors(0)
// save config
jenkins.save()
