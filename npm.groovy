def call(jsonSettings = '{}') {
defaultJsonSettings ='''{
  "emailTo":"",
  "skipTests":"False",
  "nodeSourceVersion":"12",
  "buildImage":"false",
  "skipDeploy":"false",
  "enableFortifyScan":"true",
  "forceFortifyScan":"false",
  "fortifyJavaVersion":"11",
  "forceTestNodeVer":"",
  "enableGithub":"false",
  "enableResourceBoost":"false",
  "resourceBoostValue":"1"
  }'''

globalJsonSettings = mergeJson(defaultJsonSettings, jsonSettings)
echo "defaults: ${defaultJsonSettings} \noverrides: ${jsonSettings} \nsettings: ${globalJsonSettings}"
echo "buildCausesJson: ${currentBuild.getBuildCauses().toString()}"
echo "Branch name: ${branch_name}"
def isTimerBuild = currentBuild.getBuildCauses().toString().contains("Started by timer")
def isIndexBuild = currentBuild.getBuildCauses().toString().contains("Branch indexing")
def isPushBuild = currentBuild.getBuildCauses().toString().contains("Branch event")
def isUserBuild = currentBuild.getBuildCauses().toString().contains("Started by user")
echo "buildNumber: ${currentBuild.getNumber().toString()}, isTimerBuild: ${isTimerBuild}, isIndexBuild: ${isIndexBuild}, isPushBuild: ${isPushBuild}, isUserBuild: ${isUserBuild}"
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
def node_base_image_tag = ""
def node_version = getValue(globalJsonSettings, 'nodeSourceVersion')
if (node_version == "12") {
  node_base_image_tag = "6.14-node-12"
} else if (node_version == "16") {
  node_base_image_tag = "8.19-node-16"
} else if (node_version == "18") {
  node_base_image_tag = "8.19-node-18"
} else if (node_version == "19") {
  node_base_image_tag = "8.19-node-19"
} else {
  echo "Unsupported nodeSourceVersion; Exiting"
  return
}
def fortify_base_image_tag = ""
def fortify_version = getValue(globalJsonSettings, 'fortifyJavaVersion')
if (fortify_version == "1.8") {
  fortify_base_image_tag = "22.1-jdk-8-0.1.0"
} else if (fortify_version == "11") {
  fortify_base_image_tag = "22.1-openjdk-11-slim"
} else if (fortify_version == "17") {
  fortify_base_image_tag = "22.1-openjdk-17-slim"
} else {
  echo "Unsupported fortifyVersion; Exiting"
  return
}
def testNodeVersion = getValue(globalJsonSettings, 'forceTestNodeVer')
if (testNodeVersion.length() == 0) {
  echo "Base node image: ${node_base_image_tag}"
} else {
  node_base_image_tag = "${testNodeVersion}"
  echo "Override Node Image\nBase node image: ${node_base_image_tag}"
}
echo "Using base fortify image: ${fortify_base_image_tag}"
echo "Using base node image: ${node_base_image_tag}"

// Define fortify container and logic for only deploying it when needed
def fortify_container = setResources.setFortifyContainer(globalJsonSettings, branch_name, fortify_base_image_tag)

// Boosting container resources
def node_container_resources = setResources.setContainerResources(getValue(globalJsonSettings, 'enableResourceBoost'), getValue(globalJsonSettings, 'resourceBoostValue'))

def branch_name = "${BRANCH_NAME}"
def yaml_spec = """
apiVersion: v1
kind: Pod
spec:
  initContainers:
  - name: local-cacerts
    image: image.net.com/pid/image/node:${node_base_image_tag}
    imagePullPolicy: IfNotPresent
    securityContext:
      runAsUser: 0
      runAsGroup: 0
    command:
    - sh
    - -c
    - cp -Rv /etc/ssl/certs/* /etc/ssl/certs2/.
    volumeMounts:
    - name: ssl-certs
      mountPath: /etc/ssl/certs2
  containers:
  - name: jnlp
    volumeMounts:
    - name: ssl-certs
      mountPath: /etc/ssl/certs
    env:
    - name: WHERE_AM_I
      valueFrom:
        configMapKeyRef:
          name: environment
          key: environment
  - name: node
    image: image.net.com/pid/image/node:${node_base_image_tag}
    imagePullPolicy: IfNotPresent
    ${node_container_resources}
    command:
    - cat
    tty: true
    env:
    - name: WHERE_AM_I
      valueFrom:
        configMapKeyRef:
          name: environment
          key: environment
  - name: kaniko
    image: image.net.com/pid/image/kaniko:1.9-kaniko-debug
    imagePullPolicy: IfNotPresent
    command:
    - /busybox/cat
    tty: true
  - name: nxiq
    image: image.net.com/image/nxiq-cli:1.90.0-01
    imagePullPolicy: IfNotPresent
    command:
    - cat
    tty: true
${fortify_container}
  volumes:
  - name: ssl-certs
    emptyDir: {}
"""

pipeline {
  options {
    disableConcurrentBuilds(abortPrevious: abortPrevious(branch_name) )
  }
  agent {
    kubernetes {
      defaultContainer 'jnlp'
      yaml "${yaml_spec}"
    }
  }
  environment {
    IMAGE_NAME = getRepo().toLowerCase()
    GIT_COMMIT_HASH = getGitCommitHash()
    RELEASE_TAG = getGitTag('HEAD')
    SKIP_TESTS = skipTests(getValue(globalJsonSettings, 'skipTests'))
    NXIQ_STAGE = getNxiqStage(branch_name)
    NODE_EXTRA_CA_CERTS = "/etc/ssl/certs/CA.pem"
    NPMRC = credentials('npmrc')
    PACKAGE_JSON_VERSION = versionUtils.npmVersionRead()
  }
  stages {
    stage('Manage Version') {
      when {
        allOf {
          not { expression { return branch_name == 'master' } }
          not { expression { return branch_name =~ /^support/ } }
          not { expression { return branch_name =~ /^release/ } }
        }
      }
      steps {
        container('node') {
          script {
            sh '''
               PREFIX=$(dirname ${GIT_BRANCH})
               BASENAME=$(basename ${GIT_BRANCH})
               BRANCH=$(echo ${BASENAME} | sed 's/[^-a-zA-Z_0-9]/-/g' | sed 's/--/-/g' | sed 's/-$//')
               echo $BRANCH
               VERSION=${PACKAGE_JSON_VERSION}-beta.$BRANCH.$BUILD_NUMBER
               echo $VERSION
               # Update Version Here
               npm --no-git-tag-version version $VERSION
               '''
          }
        }
      }
    }
    stage('Npm Build') {
      steps {
        container('node') {
          script {
            sh '''
              cp ${NPMRC} ~/.npmrc
              npm ci
              npm run lint
              npm run build
              npm run test
              # TODO: Report test results to jenkins
              # TODO: Collect test coverage via npm run test:coverage and report
              '''
          }
        }
      }
    }
    stage('Npm Build & Push Image') {
      when {
        expression { getValue(globalJsonSettings, 'buildImage').toBoolean()}
      }
      steps {
         container(name: 'kaniko', shell: '/busybox/sh') {
         script {
            env.VERSION = versionUtils.npmVersionRead()
            buildImages(globalJsonSettings)
          }
        }
      }
    }
    stage('Npm Deploy') {
      when {
        allOf {
          anyOf {
            expression { return branch_name == 'master' }
            expression { return branch_name == 'develop' }
            expression { return branch_name =~ /^support/ }
            expression { return branch_name =~ /^release/ }
            expression { return branch_name =~ /^feature/ }
          }
          not { expression { getValue(globalJsonSettings, 'skipDeploy').toBoolean() } }
        }
      }
      steps {
        container('node') {
          script {
            sh '''
              npm ci --omit=dev
              npm pack
              npm publish *.tgz
              '''
          }
        }
      }
    }
    stage('nxiq Scan') {
      when {
        branch 'develop'
      }
      steps {
        container('nxiq') {
          withCredentials([usernameColonPassword(credentialsId: 'nxiq-scan',variable: 'userPass')]) {
            script {
              sh '''
               applicationId=$(echo ${JOB_NAME} | awk -F "/" '{ print $1"."$2 }' )
               /usr/local/openjdk-8/bin/java -jar /nexus-iq-cli.jar -t ${NXIQ_STAGE} -s https://nxiq.net.com/ -a ${userPass} -i ${applicationId} -r nxiq.json .
              '''
            }
          } // withCredentials
        }
      }
    }
    stage('Fortify Scan') {
      when {
        allOf {
          expression { getValue(globalJsonSettings, 'enableFortifyScan').toBoolean()}
          anyOf {
            branch 'develop'
            expression { return branch_name =~ /^hotfix/ }
            expression { return branch_name =~ /^support/ }
            expression { getValue(globalJsonSettings, 'forceFortifyScan').toBoolean()}
          }
        }
      }
      steps {
        container('fortify') {
          lock('fortify') {
          sh '''
             export PATH=/opt/Fortify/bin:$PATH
             export DOT_JOB_NAME=$(echo ${JOB_NAME} |tr / .)
             #env
             #remove third party packages (scanned by nxiq, not by fortify)
             rm -rf node_modules/
             mkdir target
             mkdir target/fortify
             sourceanalyzer -b ${DOT_JOB_NAME} -clean
             sourceanalyzer -b ${DOT_JOB_NAME} -libdirs **/* **/*
             sourceanalyzer -b ${DOT_JOB_NAME} -scan -f "target/fortify/results.fpr"
             '''
          } // lock fortify
          lock(env.JOB_NAME+'/fortify') {
          withCredentials([usernameColonPassword(credentialsId: 'nxrm-fortify',variable: 'userPass')]) {
          sh '''
             export PATH=/opt/Fortify/bin:$PATH
             export DOT_JOB_NAME=$(echo ${JOB_NAME} |tr / .)
             FPRUtility -information -listIssues -search -query "Analysis:!not an issue [fortify priority order]:!low" -categoryIssueCounts -project ./target/fortify/results.fpr > ./target/fortify/results.issues
             BIRTReportGenerator -template "PCI DSS Compliance" -filterSet "Quick View" -searchQuery Analysis:!"not an issue" -source ./target/fortify/results.fpr -format PDF --Version "3.2 Compliance" --SecurityIssueDetails false -output target/fortify/results.pdf
             curl --silent --show-error --user "${userPass}" --upload-file ./target/fortify/results.fpr https://nxrm.dev.com/repository/fortify/${JOB_NAME}/commits/${GIT_COMMIT}/
             curl --silent --show-error --user "${userPass}" --upload-file ./target/fortify/results.issues https://nxrm.dev.com/repository/fortify/${JOB_NAME}/commits/${GIT_COMMIT}/
             curl --silent --show-error --user "${userPass}" --upload-file ./target/fortify/results.pdf https://nxrm.dev.com/repository/fortify/${JOB_NAME}/commits/${GIT_COMMIT}/

             curl --silent --show-error --fail --user "${userPass}" --output ./target/fortify/${DOT_JOB_NAME}.fpr \
                  -X GET https://nxrm.dev.com/repository/fortify/${JOB_NAME}/${DOT_JOB_NAME}.fpr || echo "${DOT_JOB_NAME}.fpr does not exist. Nothing to merge."
             if [ -f "./target/fortify/${DOT_JOB_NAME}.fpr" ]; then
               FPRUtility -merge -project ./target/fortify/${DOT_JOB_NAME}.fpr -source ./target/fortify/results.fpr -f ./target/fortify/merged.fpr
               FPRUtility -information -listIssues -search -query "Analysis:!not an issue [fortify priority order]:!low" -categoryIssueCounts -project ./target/fortify/merged.fpr > ./target/fortify/merged.issues
               BIRTReportGenerator -template "PCI DSS Compliance" -filterSet "Quick View" -searchQuery Analysis:!"not an issue" -source ./target/fortify/merged.fpr -format PDF --Version "3.2 Compliance" --SecurityIssueDetails false -output target/fortify/merged.pdf
               # Upload fpr
               curl --silent --show-error --user "${userPass}" --upload-file ./target/fortify/merged.fpr https://nxrm.dev.com/repository/fortify/${JOB_NAME}/commits/${GIT_COMMIT}/
               curl --silent --show-error --user "${userPass}" --upload-file ./target/fortify/merged.fpr https://nxrm.dev.com/repository/fortify/${JOB_NAME}/${DOT_JOB_NAME}.fpr
               # Upload .issues
               curl --silent --show-error --user "${userPass}" --upload-file ./target/fortify/merged.issues https://nxrm.dev.com/repository/fortify/${JOB_NAME}/commits/${GIT_COMMIT}/
               curl --silent --show-error --user "${userPass}" --upload-file ./target/fortify/merged.issues https://nxrm.dev.com/repository/fortify/${JOB_NAME}/${DOT_JOB_NAME}.issues
               # Upload .pdf
               curl --silent --show-error --user "${userPass}" --upload-file ./target/fortify/merged.pdf https://nxrm.dev.com/repository/fortify/${JOB_NAME}/commits/${GIT_COMMIT}/
               curl --silent --show-error --user "${userPass}" --upload-file ./target/fortify/merged.pdf https://nxrm.dev.com/repository/fortify/${JOB_NAME}/${DOT_JOB_NAME}.pdf

             else
               curl --silent --show-error --user "${userPass}" --upload-file ./target/fortify/results.fpr https://nxrm.dev.com/repository/fortify/${JOB_NAME}/${DOT_JOB_NAME}.fpr
               curl --silent --show-error --user "${userPass}" --upload-file ./target/fortify/results.issues https://nxrm.dev.com/repository/fortify/${JOB_NAME}/${DOT_JOB_NAME}.issues
               curl --silent --show-error --user "${userPass}" --upload-file ./target/fortify/results.pdf https://nxrm.dev.com/repository/fortify/${JOB_NAME}/${DOT_JOB_NAME}.pdf
             fi
             '''
          } //withCredentials
          } //lock env.JOB_NAME+'/fortify'
        }
      }
    }
  }
  post {
    always {
      script {
        def subjectPrefix = ""
        if (!"production".equals(env.WHERE_AM_I)) {
          subjectPrefix = "[ ${env.WHERE_AM_I} ] "
        }
        emailTo = getValue(globalJsonSettings, 'emailTo')
        def subject = "${subjectPrefix}${currentBuild.result} -=-=- ${env.JOB_NAME} [${env.BUILD_NUMBER}] "
        def details = " ${env.BUILD_URL} ${env.JOB_NAME} [${env.BUILD_NUMBER}] "
        emailext (
          to: emailTo,
          recipientProviders: [[$class: 'CulpritsRecipientProvider'],
                               [$class: 'RequesterRecipientProvider']],
                              //  [$class: 'DevelopersRecipientProvider']],
          subject: subject,
          body: details,
          attachLog: true
        )
      }
    }
  }
}
} //call

This pipeline gives me an error E401 incorrect or missing password