def call() {
  // ... (existing code for shared script)
}

def buildImages(settings) {
  def imageTag = getValue(settings, 'imageTag', 'latest')
  def dockerfilePath = getValue(settings, 'dockerfilePath', './Dockerfile')

  // Build the Docker images using kaniko
  sh "executor --dockerfile=${dockerfilePath} --destination=${IMAGE_NAME}:${imageTag} --context=${WORKSPACE}"
}

def pushToGCP(registry, settings) {
  def imageTag = getValue(settings, 'imageTag', 'latest')
  def imageName = getImageName(settings)

  // Set GCP project ID based on the environment and registry
  def gcpProjectId
  if (registry == "devops") {
    gcpProjectId = "devops-project-id"
  } else if (registry == "sandbox") {
    gcpProjectId = "sandbox-project-id"
  } else if (registry == "ncdeDev") {
    gcpProjectId = "ncde-dev-project-id"
  } else if (registry == "ncdeQA") {
    gcpProjectId = "ncde-qa-project-id"
  } else if (registry == "ncdeCert") {
    gcpProjectId = "ncde-cert-project-id"
  } else if (registry == "ncdeProd") {
    gcpProjectId = "ncde-prod-project-id"
  } else {
    error("Unknown registry: ${registry}")
    return
  }

  // Authenticate with GCP and push the image
  sh "gcloud auth activate-service-account --key-file=${GOOGLE_APPLICATION_CREDENTIALS}"
  sh "gcloud --project=${gcpProjectId} docker -- push gcr.io/${gcpProjectId}/${imageName}:${imageTag}"
}

// Helper function to get the image name
def getImageName(settings) {
  def imageName = getValue(settings, 'imageName', 'my-app')
  return imageName
}

// Helper function to safely get values from JSON
def getValue(settings, key, defaultValue = null) {
  def json = new groovy.json.JsonSlurper().parseText(settings)
  return json.has(key) ? json[key] : defaultValue
}
==================================================================================

def call(globalJsonSettings = '{}') {
  // ... (existing code for call function)

  stages {
    stage('Git Push') {
      // ... (existing code for Git Push stage)
    }

    stage('Manage Version') {
      // ... (existing code for Manage Version stage)
    }

    stage('nxiq quick scan') {
      // ... (existing code for nxiq quick scan stage)
    }

    stage('Maven Deploy') {
      // ... (existing code for Maven Deploy stage)
    }

    stage('Build') {
      steps {
        container('kaniko', shell: '/busybox/sh') {
          script {
            // Call the buildImages function from the shared script to build the images
            buildImages(globalJsonSettings)
          }
        }
      }
    }
    stage('Prisma Cloud Scan') {
      steps {
        withCredentials([file(credentialsId: 'prismaCloudImageScan.enc.yaml', variable: 'PRISMA_CLOUD_CREDS')]) {
          // Assuming you have a Prisma Cloud plugin installed on your Jenkins instance,
          // use the credentials for the Prisma Cloud scan.
          prismaCloudScan(
            accessKeyId: PRISMA_CLOUD_CREDS,
            secretAccessKey: PRISMA_CLOUD_CREDS,
            // Other Prisma Cloud scan configurations as needed.
          )
        }
      }
    }
    stage('Push Image') {
      steps {
        script {
          withCredentials([file(credentialsId: 'pid-ops.json', variable: 'GOOGLE_APPLICATION_CREDENTIALS')]) {
            // Push the images to GCP using the pushImages function from the shared script
            pushImages.pushToGCP("devops", imageJsonSettings)
          }

          if (getValue(imageJsonSettings, 'pushToGCP') == 'true') {
            withCredentials([file(credentialsId: 'pid-sbx.json', variable: 'GOOGLE_APPLICATION_CREDENTIALS')]) {
              pushImages.pushToGCP("sandbox", imageJsonSettings)
            }
            withCredentials([file(credentialsId: 'pid-dev.json', variable: 'GOOGLE_APPLICATION_CREDENTIALS')]) {
              pushImages.pushToGCP("ncdeDev", imageJsonSettings)
            }
            if (isDevelopBranch(env.BRANCH_NAME) || isReleaseBranch(env.BRANCH_NAME)) {
              withCredentials([file(credentialsId: 'pid-qa.json', variable: 'GOOGLE_APPLICATION_CREDENTIALS')]) {
                pushImages.pushToGCP("ncdeQA", imageJsonSettings)
              }
            }
            if (isReleaseBranch(env.BRANCH_NAME) && !IMAGE_TAG.contains('SNAPSHOT')) {
              withCredentials([file(credentialsId: 'pid-cert.json', variable: 'GOOGLE_APPLICATION_CREDENTIALS')]) {
                pushImages.pushToGCP("ncdeCert", imageJsonSettings)
              }
              withCredentials([file(credentialsId: 'pid-prod.json', variable: 'GOOGLE_APPLICATION_CREDENTIALS')]) {
                pushImages.pushToGCP("ncdeProd", imageJsonSettings)
              }
              sh 'rm -v /image.tar'
            }
          }
        }
      }
    }

    stage('Prisma Cloud Scan') {
      steps {
        withCredentials([file(credentialsId: 'prismaCloudImageScan.enc.yaml', variable: 'PRISMA_CLOUD_CREDS')]) {
          // Assuming you have a Prisma Cloud plugin installed on your Jenkins instance,
          // use the credentials for the Prisma Cloud scan.
          prismaCloudScan(
            accessKeyId: PRISMA_CLOUD_CREDS,
            secretAccessKey: PRISMA_CLOUD_CREDS,
            // Other Prisma Cloud scan configurations as needed.
          )
        }
      }
    }

    // ... (existing code for nxiq Scan and Fortify Scan stages)

  } // End of stages

  post {
    // ... (existing code for post actions)
  }
} // End of call function
