def call(jsonSettings = '{}') {
defaultJsonSettings = '''{
  "emailTo":"",
  "enableGithub":"false"
  }'''
globalJsonSettings = mergeJson(defaultJsonSettings, jsonSettings)
echo "defaults: ${defaultJsonSettings} \noverrides: ${jsonSettings} \nsettings: ${globalJsonSettings}"
// derived from https://hopstorawpointers.blogspot.com/2018/12/nightly-build-steps-with-jenkins.html
def isTimerBuild = currentBuild.getBuildCauses().toString().contains("Started by timer")
def isIndexBuild = currentBuild.getBuildCauses().toString().contains("Branch indexing")
def isPushBuild = currentBuild.getBuildCauses().toString().contains("Branch event")
def isUserBuild = currentBuild.getBuildCauses().toString().contains("Started by user")
echo "buildNumber: ${currentBuild.getNumber().toString()}, isTimerBuild: ${isTimerBuild}, isIndexBuild: ${isIndexBuild}, isPushBuild: ${isPushBuild}, isUserBuild: ${isUserBuild}"
// this if clause must not be wrapped in a node/stage for the `return`
//  to properly exit the job.
if (isIndexBuild) { // Build Triggered by Project/Branch Index Event. ie: Jenkins rebuilt.
  echo "This build was triggered by an Indexing event. No work will be done."
  currentBuild.result = 'ABORTED'
  return
}
// Enable pipeline movement from BB to GHE
// Will stop pipeline executing from wrong SCM
def ENABLE_GITHUB = getValue(globalJsonSettings, 'enableGithub').toLowerCase()
def REPOSITORY_URL = scm.userRemoteConfigs[0].url
echo "REPOSITORY_URL: ${REPOSITORY_URL}"
echo "ENABLE_GITHUB: ${ENABLE_GITHUB}"
// Repo location: BB; GHE enabled: F -> Pass
if (ENABLE_GITHUB == 'false' && REPOSITORY_URL.contains("bitbucket")) {
    echo "Bitbucket Pipeline is running for ${REPOSITORY_URL}."
// Repo location: BB; GHE enabled: T -> Fail
} else if (ENABLE_GITHUB == 'true' && REPOSITORY_URL.contains("bitbucket")) {
    echo "GitHub pipeline has been enabled, but the repository is hosted on BitBucket. Aborting Bitbucket pipeline."
    currentBuild.result = 'ABORTED'
// Repo location: GHE; GHE enabled: F -> Fail
} else if (ENABLE_GITHUB == 'false' && REPOSITORY_URL.contains("github")) {
    error('GitHub integration is disabled. Aborting GitHub pipeline.')
    currentBuild.result = 'ABORTED'
// Repo location: GHE; GHE enabled: T -> Pass
} else if (ENABLE_GITHUB == 'true' && REPOSITORY_URL.contains("github")) {
    echo "Pipeline is running for ${REPOSITORY_URL}."
    // Continue Pipeline
}
def branch_name = "${BRANCH_NAME}"
pipeline {
  options {
    disableConcurrentBuilds(abortPrevious: abortPrevious(branch_name))
  }
  agent {
    kubernetes {
      defaultContainer 'jnlp'
      yaml """
apiVersion: v1
kind: Pod
spec:
  initContainers:
  - name: local-cacerts
    image: image-nxrm.greyops.touchnet.com/image/alpine:3.12.0-0.0.1
    imagePullPolicy: IfNotPresent
    securityContext:
      runAsUser: 0
      runAsGroup: 0
    command:
    - sh
    - -c
    - cp -Rv /etc/ssl/certs/* /etc/ssl/certs2/.
    volumeMounts:
    - mountPath: /etc/ssl/certs2
      name: ssl-certs
  containers:
  - name: jnlp
    securityContext:
      runAsUser: 1000
      runAsGroup: 1000
    volumeMounts:
    - mountPath: /etc/ssl/certs
      name: ssl-certs
  - name: kaniko
    image: image-nxrm.greyops.touchnet.com/pid-gousgnag-tnet-devops/image/kaniko:1.9-kaniko-debug
    imagePullPolicy: IfNotPresent
    command:
    - /busybox/cat
    tty: true
  - name: alpine
    image: image-nxrm.greyops.touchnet.com/image/alpine:3.12.0-0.0.1
    imagePullPolicy: IfNotPresent
    securityContext:
      runAsUser: 1000
      runAsGroup: 1000
    command:
    - cat
    tty: true
  volumes:
  - name: tn-root-ca
    secret:
      secretName: tn-root-ca
      items:
      - key: TouchNetPrimaryCAroot.crt
        path: TouchNetPrimaryCAroot.crt
  - name: ssl-certs
    emptyDir: {}
      """
    }
  }

  environment {
    PROJECT_REPO = getRepo().toLowerCase()
    GIT_COMMIT_HASH = sh (script: "git log -n 1 --pretty=format:'%H'", returnStdout: true)
    VERSION = versionUtils.simpleVersionRead()
    IS_RELEASE_BRANCH = isReleaseBranch(branch_name)
    IS_DEVELOP_BRANCH = isDevelopBranch(branch_name)
    GCR_LOCATION = 'params.IMAGE_LOCATION?: "gcr.io/my-project"'
    IMAGE_NAME = 'image-\${env.GITHUB_REPOSITORY#*/}'
    GCLOUD_SERVICE_KEY = crendentials('gcloud_service_key')
  } 
  parameters {
  string(name: 'IMAGE_LOCATION', description: 'The location to store the container image in GCR')
  string(name: 'IMAGE_LIFECYCLE', defaultValue: 'short', description: 'the lifecycle of the container image: short or long')
  }
  stages {
    stage('init') {
      steps {
        container('alpine') {
          sh '''
            if [ -x init ]
            then
              ./init
            fi
          '''
        }
      }
    }
    stage('Image Build & Push ') {
      steps {
        container(name: 'kaniko', shell: '/busybox/sh') {
          script {
            def imageLocation = env.IMAGE_LOCATION ?: "gcr.io/my-project"
            def imageFullName = "${imageLocation}/${env.IMAGE_NAME}"
            sh "echo ${GCLOUD_SERVICE_KEY} > /kaniko/.docker/config.json"
            sh "echo ${GCLOUD_SERVICE_KEY} > /kaniko/.docker/key.json"
            sh "cat /kaniko/.docker/key.json | docker login -u _json_key --password-stdin https://${imageLocation}"
            sh "docker build -t ${imageFullName} ."
            sh "docker push ${imageFullName} --label=lifecycle=${params.IMAGE_LIFECYCLE}"
            // buildImages(globalJsonSettings)
          }
        }
      }
    }
  }
  post {
    always {
        script {
          def emailTo = getValue(globalJsonSettings, 'emailTo')
          def subject = " ${currentBuild.result} -=-=- ${env.JOB_NAME} [${env.BUILD_NUMBER}] "
          def details = " ${env.BUILD_URL} ${env.JOB_NAME} [${env.BUILD_NUMBER}] "
          emailext (
            to: emailTo,
            recipientProviders: [[$class: 'CulpritsRecipientProvider'],
                                 [$class: 'RequesterRecipientProvider']],
                                 //  [$class: 'DevelopersRecipientProvider']],            
            subject: subject,
            body: details,
            attachLog: true
        )
      }
    }
    success {
      script {
        scheduleBuild(env.BRANCH_NAME, getValue(globalJsonSettings, 'scheduleFromDevelop'), getValue(globalJsonSettings, 'scheduleFromMaster') )
      }
    }
  }
}
} //def call

