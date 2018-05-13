import org.csanchez.jenkins.plugins.kubernetes.*
import jenkins.model.*
import com.cloudbees.plugins.credentials.CredentialsScope
import com.cloudbees.plugins.credentials.SystemCredentialsProvider
import com.cloudbees.plugins.credentials.domains.Domain
import org.csanchez.jenkins.plugins.kubernetes.volumes.PodVolume
import hudson.util.Secret
import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl
import hudson.security.*
import hudson.plugins.git.*
import java.util.ArrayList

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
def localIp = InetAddress.localHost.hostAddress
// set variables based on env
String jenkins_admin_username = "admin"
String jenkins_admin_password = "*Passw0rd123!"
String jenkins_k8s_secret = "jenkins_secret"
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

def kubernetesCloud = new KubernetesCloud(
  'kubernetes',
  new ArrayList<PodTemplate>(),
  'https://kubernetes',
  'default',
  "http://${localIp}:8080".toString(),
  '10', 60, 0, 5
)
kubernetesCloud.setSkipTlsVerify(true)
kubernetesCloud.setCredentialsId('kubernetes-admin')


podTemplate = new PodTemplate(jenkinsnode_image, new ArrayList<PodVolume>())

List<ContainerTemplate> containerTemplates = [
  new ContainerTemplate('jnlp', jenkinsnode_image, 'jenkins-slave', '')
]
podTemplate = new PodTemplate(jenkinsnode_image, new ArrayList<PodVolume>())
podTemplate.setName('jenkins-worker')
podTemplate.setNamespace('default')
podTemplate.setLabel('jenkins-worker')
podTemplate.setContainers(containerTemplates)

kubernetesCloud.addTemplate(podTemplate)
jenkins.clouds.replace(kubernetesCloud)
jenkins.save()
// set jenkins number od executors
jenkins.setNumExecutors(0)


// prepare k8s-ros-jenkins job
def scmJenkins = new GitSCM("https://github.com/szymonrychu/k8s-ros-jenkins")
scmJenkins.branches = [
  new BranchSpec('') // empty for all branches
];
def jobJenkins = new org.jenkinsci.plugins.workflow.job.WorkflowJob(jenkins, "[DockerPipeline] Jenkins")
jobJenkins.definition = new org.jenkinsci.plugins.workflow.cps.CpsScmFlowDefinition(scmJenkins, "Jenkinsfile")
// prepare k8s-ros-jenkinsnode job
def scmJenkinsnode = new GitSCM("https://github.com/szymonrychu/k8s-ros-jenkinsnode")
scmJenkinsnode.branches = [
  new BranchSpec('') // empty for all branches
];
def jobJenkinsnode = new org.jenkinsci.plugins.workflow.job.WorkflowJob(jenkins, "[DockerPipeline] Jenkins Node")
jobJenkinsnode.definition = new org.jenkinsci.plugins.workflow.cps.CpsScmFlowDefinition(scmJenkinsnode, "Jenkinsfile")
// prepare k8s-ros-master job
def scmRosMaster = new GitSCM("https://github.com/szymonrychu/k8s-ros-master")
scmRosMaster.branches = [
  new BranchSpec('') // empty for all branches
];
def jobRosMaster = new org.jenkinsci.plugins.workflow.job.WorkflowJob(jenkins, "[DockerPipeline] ROS master")
jobRosMaster.definition = new org.jenkinsci.plugins.workflow.cps.CpsScmFlowDefinition(scmRosMaster, "Jenkinsfile")



// save config
jenkins.reload()
jenkins.save()
