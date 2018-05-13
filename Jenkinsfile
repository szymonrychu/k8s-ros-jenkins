node('jenkins-worker') {
  timestamps {
  ansiColor('xterm') {
  wrap([$class: 'BuildUser']) {
    stage('Get env'){
      sh 'env'
    }
  }}} //BuildUser //ansiColor //timestamps
}
