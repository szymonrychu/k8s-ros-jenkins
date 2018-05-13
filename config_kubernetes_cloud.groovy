import hudson.util.*
import hudson.model.*
import hudson.security.*
import jenkins.model.*
import jenkins.branch.*
import jenkins.plugins.git.*
import org.csanchez.jenkins.plugins.kubernetes.*
import org.csanchez.jenkins.plugins.kubernetes.volumes.PodVolume
import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl
import org.jenkinsci.plugins.workflow.multibranch.*
import com.cloudbees.plugins.credentials.*
import com.cloudbees.plugins.credentials.domains.*
import com.cloudbees.hudson.plugins.folder.*
import com.cloudbees.hudson.plugins.folder.computed.*

import java.util.ArrayList

// get master objects
jenkins = Jenkins.getInstance()
environmentalVariables = System.getenv()
localIp = InetAddress.localHost.hostAddress

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
def createMultibranchPipeline(String folderName, String projectName, String gitRepo, String gitRepoName, String credentialsId){
  // Get the folder where this job should be
  def folder = jenkins.getItem(folderName)
  // Create the folder if it doesn't exist
  if (folder == null) {
    folder = jenkins.createProject(Folder.class, folderName)
  }

  // Multibranch creation/update
  WorkflowMultiBranchProject mbp
  Item item = folder.getItem(projectName)
  if ( item != null ) {
    // Update case
    mbp = (WorkflowMultiBranchProject) item
  } else {
    // Create case
    mbp = folder.createProject(WorkflowMultiBranchProject.class, projectName)
  }

  // Configure the script this MBP uses
  mbp.getProjectFactory().setScriptPath('Jenkins')

  // Add git repo
  String id = null
  String remote = gitRepo
  String includes = "*"
  String excludes = ""
  boolean ignoreOnPushNotifications = false
  GitSCMSource gitSCMSource = new GitSCMSource(id, remote, credentialsId, includes, excludes, ignoreOnPushNotifications)
  BranchSource branchSource = new BranchSource(gitSCMSource)

  // for (var f in Jenkins.instance.getAllItems(jenkins.branch.MultiBranchProject.class) {
  //   if (f.parent instanceof jenkins.branch.OrganizationFolder) {
  //     // managed by org folder, leave alone
  //     continue;
  //   }
  //   // addTrigger will replace an existing one
  //   f.addTrigger(new com.cloudbees.hudson.plugins.folder.computedPeriodicFolderTrigger("1d"));
  // }
  mbp.addTrigger(new PeriodicFolderTrigger("1m"));

  // Remove and replace?
  PersistedList sources = mbp.getSourcesList()
  sources.clear()
  sources.add(branchSource)
}
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








createMultibranchPipeline('Docker Pipelines',
  'Jenkins',
  'https://github.com/szymonrychu/k8s-ros-jenkins',
  'docker_source', null
)
createMultibranchPipeline('Docker Pipelines',
  'Jenkinsnode',
  'https://github.com/szymonrychu/k8s-ros-jenkinsnode',
  'docker_source', null
)
createMultibranchPipeline('Docker Pipelines',
  'ROS master',
  'https://github.com/szymonrychu/k8s-ros-master',
  'docker_source', null
)

// save config
jenkins.reload()
jenkins.save()
