stage('Prisma Cloud Image Scan') {
    steps {
        script {
            // Assuming you have already built and pushed the Docker image
            def imageName = 'your-docker-image-name:latest'
            
            // Run Prisma Cloud image scan
            def imageScanResults = prismadata.prismaImageScan(
                imageName: imageName,
                cloudConfig: 'my-prisma-cloud-config',
                failBuild: true // Fail the build if vulnerabilities are found
            )

            // Check image scan results for vulnerabilities
            if (imageScanResults.vulnerabilities) {
                error("Prisma Cloud image scan detected vulnerabilities. Failing the build.")
            } else {
                echo "Prisma Cloud image scan passed. No vulnerabilities found."
            }
        }
    }
}



stage('Prisma Cloud Image Scan') {
    steps {
        script {
            // Assuming you have already built and pushed the Docker image
            def imageName = 'your-docker-image-name:latest'
            
            // Run Prisma Cloud image scan
            prismaCloudScan (
                imageName: imageName,
                cloudConfig: 'my-prisma-cloud-config'
            )

            // Check image scan results for vulnerabilities
            def scanResults = prismaCloudGetScanResults imageName: imageName
            if (scanResults.vulnerabilities) {
                error("Prisma Cloud image scan detected vulnerabilities. Failing the build.")
            } else {
                echo "Prisma Cloud image scan passed. No vulnerabilities found."
            }
        }
    }
}





pipeline {
    agent any

    // Define the list of Docker image names and tags
    environment {
        DOCKER_IMAGES = [
            "your-docker-image-name-1:tag-1",
            "your-docker-image-name-2:tag-2",
            "your-docker-image-name-3:tag-3"
            // Add more image names and tags as needed
        ]
    }

  stages {
      stage('Prisma Cloud Image Scan') {
        when {
        expression { getValue(globalJsonSettings, 'enablePrismaCloudScan').toBoolean() }
        }
          steps {
              script {
                  // Iterate over each image and tag in the list
                  for (def imageWithTag in DOCKER_IMAGES) {
                      // Extract the image name and tag from the list item
                      def (imageName, imageTag) = imageWithTag.tokenize(':')
                      def completeImageName = "${imageName}:${imageTag}"
                      
                      // Run Prisma Cloud image scan
                      prismaCloudScan (
                          imageName: completeImageName,
                          cloudConfig: 'my-prisma-cloud-config'
                      )
  
                      // Check image scan results for vulnerabilities
                      def scanResults = prismaCloudGetScanResults imageName: completeImageName
                      if (scanResults.vulnerabilities) {
                          error("Prisma Cloud image scan detected vulnerabilities for ${completeImageName}. Failing the build.")
                      } else {
                          echo "Prisma Cloud image scan passed for ${completeImageName}. No vulnerabilities found."
                      }
                  }
              }
          }
      }
      // Add more stages as needed
  }
  post {
      // Post-build actions, if any
  }





//latest

stage('Prisma Cloud Image Scan') {
    when {
        expression { getValue(globalJsonSettings, 'enablePrismaCloudScan').toBoolean() }
    }
    steps {
        script {
            // Iterate over each image and tag in the list
            for (def imageWithTag in DOCKER_IMAGES) {
                // Extract the image name and tag from the list item
                def (imageName, imageTag) = imageWithTag.tokenize(':')
                def completeImageName = "${imageName}:${imageTag}"

                // Run Prisma Cloud image scan
                prismaCloudScan(
                    imageName: completeImageName,
                    cloudConfig: 'my-prisma-cloud-config'
                )

                // Check image scan results for vulnerabilities
                def scanResults = prismaCloudGetScanResults(imageName: completeImageName)
                if (scanResults.vulnerabilities) {
                    error("Prisma Cloud image scan detected vulnerabilities for ${completeImageName}. Failing the build.")
                } else {
                    echo "Prisma Cloud image scan passed for ${completeImageName}. No vulnerabilities found."
                }
            }
        }
    }
}
// Add more stages as needed






stage('Prisma Cloud Image Scan') {
  steps {
    // Assuming you have the necessary Prisma Cloud credentials stored as a Jenkins secret
    withCredentials([string(credentialsId: 'prisma-cloud-credentials', variable: 'PRISMA_CLOUD_CREDENTIALS')]) {
      container(name: 'kaniko', shell: '/busybox/sh') {
        script {
          // Build the Docker image as usual using kaniko or any other tool
          buildImages(globalJsonSettings)

          // Get the image name and tag (adjust this based on how you tag your images)
          def imageName = "your-docker-registry/your-image-name"
          def imageTag = "latest"

          // Prisma Cloud image scanning
          sh '''
            docker login -u your-docker-registry-username -p your-docker-registry-password
            docker pull ${imageName}:${imageTag}
            docker tag ${imageName}:${imageTag} ${imageName}:prisma-scan

            # Run Prisma Cloud image scanning (adjust the parameters as needed)
            docker run --rm -e PRISMA_ACCESS_TOKEN="${PRISMA_CLOUD_CREDENTIALS}" -e GATEWAY_URL="https://prisma-cloud-url" -e IMAGE="${imageName}:prisma-scan" twistlock/cli:latest images scan
          '''
        }
      }
    }
  }
}


stage('Prisma Cloud Image Scanning') {
  steps {
    withCredentials([usernamePassword(credentialsId: 'prisma-cloud-credentials', usernameVariable: 'PRISMA_CLOUD_USERNAME', passwordVariable: 'PRISMA_CLOUD_PASSWORD')]) {
      script {
        def prismaCloudImage = "your-registry/your-image:${env.GIT_COMMIT}"
        def prismaCloudProject = "your-prisma-cloud-project"
        def prismaCloudRegistry = "your-registry"

        def scanId = sh(script: "prismacloud_scan --project ${prismaCloudProject} --registry ${prismaCloudRegistry} --image ${prismaCloudImage} --username ${PRISMA_CLOUD_USERNAME} --password ${PRISMA_CLOUD_PASSWORD}", returnStdout: true).trim()
        echo "Prisma Cloud image scan triggered. Scan ID: ${scanId}"

        // Wait for the scan to complete and retrieve scan results
        def scanStatus = ""
        while (scanStatus != "completed") {
          sleep(30) // Adjust the sleep interval as needed
          scanStatus = sh(script: "prismacloud_scan_status --scanid ${scanId} --username ${PRISMA_CLOUD_USERNAME} --password ${PRISMA_CLOUD_PASSWORD}", returnStdout: true).trim()
          echo "Prisma Cloud scan status: ${scanStatus}"
        }

        // Retrieve the scan report and process the results as needed
        def scanReport = sh(script: "prismacloud_scan_report --scanid ${scanId} --username ${PRISMA_CLOUD_USERNAME} --password ${PRISMA_CLOUD_PASSWORD}", returnStdout: true).trim()
        echo "Prisma Cloud scan report:"
        echo scanReport

        // You can add further logic here based on the scan results, like failing the build if vulnerabilities exceed a certain threshold.
        // For simplicity, this example does not include such logic.
      }
    }
  }
}

======================================================
def buildImages(globalJsonSettings = '{}') {
    // Your existing buildImages logic goes here...

    // Perform Prisma Cloud Image Scan
    try {
        withCredentials([file(credentialsId: 'prismaCloudImageScan.enc.yaml', variable: 'PRISMA_CLOUD_CREDENTIALS')]) {
            // Use the 'prismaCloudScan' step provided by the Prisma Cloud plugin
            // Replace 'IMAGE_NAME' and 'IMAGE_TAG' with your actual image name and tag
            prismaCloudScan credentialsId: 'PRISMA_CLOUD_CREDENTIALS', imageName: 'IMAGE_NAME', imageTag: 'IMAGE_TAG'
        }
    } catch (err) {
        message = "Prisma Cloud Image Scan failed: " + err.getMessage()
        catchError(message: message, buildResult: 'FAILURE', stageResult: 'FAILURE') {
            echo message
        }
        currentBuild.result = 'FAILURE'
        throw err
    }
}



====================================
pipeline {
    agent any

    stages {
        stage('Build & Push Image') {
            steps {
                container(name: 'kaniko', shell: '/busybox/sh') {
                    script {
                        try {
                            buildImages(globalJsonSettings)
                        } catch (Exception e) {
                            currentBuild.result = 'FAILURE'
                            error('Prisma Cloud image scanning found vulnerabilities.')
                        }
                    }
                }
            }
        }
    }
}
================================================================
       stage('Scan') {
            steps {
                // Scan the image
                prismaCloudScanImage ca: '',
                cert: '',
                dockerAddress: 'unix:///var/run/docker.sock',
                image: 'test/test-image*',
                key: '',
                logLevel: 'info',
                podmanPath: '',
                // The project field below is only applicable if you are using Prisma Cloud Compute Edition and have set up projects (multiple consoles) on Prisma Cloud.
                project: '',
                resultsFile: 'prisma-cloud-scan-results.json',
                ignoreImageBuildTime:true
            }
        }
    }

=======================================================================================

withCredentials([file(credentialsId: 'nxrm.docker.config.json', variable: 'NXRM_DOCKER_CONFIG_JSON')]) {
      try {
        sh '''
          set -xe
          cp ${NXRM_DOCKER_CONFIG_JSON} /kaniko/.docker/config.json
          /kaniko/executor --log-format=text \
            --dockerfile=${WORKSPACE}/${DOCKERFILE} \
            --context=dir://${WORKSPACE}${DIRNAME} \
            --verbosity=info \
            --cache=${USE_CACHE} ${REPRODUCIBLE} \
            --cache-repo=${NXRM_PATH}/cache \
            --destination=image \
            --no-push \
            --tarPath=/image.tar \
            --cleanup \
            --label gitCommitHash=${GIT_COMMIT_HASH} \
            && mkdir -p /workspace \
            && rm /kaniko/.docker/* # https://github.com/GoogleContainerTools/kaniko/issues/1232
        '''
      } catch (err) {
        message = "BUILD FAILURE: " + err.getMessage()
        catchError(message: message, buildResult: 'FAILURE', stageResult: 'FAILURE') {
          echo message
        }
        currentBuild.result = 'FAILURE'
        throw err
      }
    }
    explain this code and give me the varialble name i can use to call the dcoker image

    ==========================================

    stages {
        stage('Check twistcli version') {
            steps {
                script {
                    // Retrieve Prisma Cloud credentials using the plugin
                    def credentials = prismaCloudCredentials('prisma-cloud-credentials-id')

                    // Set environment variables for Twistcli
                    env.TL_USER = credentials.TL_USER
                    env.TL_PASS = credentials.TL_PASSWORD
                }
                // Your existing 'Check twistcli version' stage code here
            }
        }
        stage('Scan with Twistcli') {
            steps {
                script {
                    // Retrieve Prisma Cloud credentials using the plugin (optional if already retrieved)
                    def credentials = prismaCloudCredentials('prisma-cloud-credentials-id')

                    // Set environment variables for Twistcli (optional if already set)
                    env.TL_USER = credentials.TL_USER
                    env.TL_PASS = credentials.TL_PASSWORD
                }
                // Your existing 'Scan with Twistcli' stage code here
                stage('Scan with Twistcli') {
                  sh "./twistcli images scan --address https://$TL_CONSOLE -u $TL_USER -p $TL_PASS --details ${imageName}"
}

            }
        }
    }
}
======================
======================
pipeline {
    agent any // You can specify the Jenkins agent to run the pipeline on a specific node

    environment {
        // Define environment variables for Prisma Cloud credentials
        PRISMA_CLOUD_CREDENTIALS_ID = 'prisma-cloud-credentials-id'
        TL_CONSOLE = 'prisma-cloud-console-url'
        dockerImages = ['image1:tag1', 'image2:tag2', 'image3:tag3'] // Add your list of Docker images here
    }

    stages {
        stage('Build and Scan Docker Images') {
            steps {
                script {
                    // Retrieve Prisma Cloud credentials using the plugin
                    def credentials = prismaCloudCredentials(PRISMA_CLOUD_CREDENTIALS_ID)

                    // Set environment variables for Twistcli
                    env.TL_USER = credentials.TL_USER
                    env.TL_PASS = credentials.TL_PASSWORD

                    // Loop through the list of Docker images
                    for (def image in dockerImages) {
                        // Build Docker image using Kaniko
                        sh '''
                            set -xe
                            cp ${NXRM_DOCKER_CONFIG_JSON} /kaniko/.docker/config.json
                            /kaniko/executor --log-format=text \
                              --dockerfile=${WORKSPACE}/${DOCKERFILE} \
                              --context=dir://${WORKSPACE}${DIRNAME} \
                              --verbosity=info \
                              --cache=${USE_CACHE} ${REPRODUCIBLE} \
                              --cache-repo=${NXRM_PATH}/cache \
                              --destination=${image} \
                              --tarPath=/image.tar \
                              --cleanup \
                              --label gitCommitHash=${GIT_COMMIT_HASH} \
                              && mkdir -p /workspace
                        '''

                        // Scan the built Docker image using Twistcli
                        sh "./twistcli images scan --address ${TL_CONSOLE} -u ${TL_USER} -p ${TL_PASS} --details ${image}"
                    }
                }
            }
        }
    }
}

// Function to retrieve Prisma Cloud credentials
def prismaCloudCredentials(credentialsId) {
    return credentials(credentialsId: credentialsId)
}
==================================================================
// Define a shared function for building and scanning Docker images
def buildAndScanDockerImages(String credentialsId, String tlConsole, List<String> imageNames) {
    // Retrieve Prisma Cloud credentials from the encrypted file
    withCredentials([file(credentialsId: 'prismaCloudImageScan.enc.yaml')]) {
        def prismaCredentials = readYaml(file: 'prismaCloudImageScan.enc.yaml')
        env.TL_USER = prismaCredentials.TL_USER
        env.TL_PASS = prismaCredentials.TL_PASSWORD
    }

    // Build the Docker image using Kaniko for each image in the list
    imageNames.each { imageName ->
        withCredentials([file(credentialsId: 'nxrm.docker.config.json', variable: 'NXRM_DOCKER_CONFIG_JSON')]) {
            try {
                sh '''
                    set -xe
                    cp ${NXRM_DOCKER_CONFIG_JSON} /kaniko/.docker/config.json
                    /kaniko/executor --log-format=text \
                        --dockerfile=${WORKSPACE}/${DOCKERFILE} \
                        --context=dir://${WORKSPACE}${DIRNAME} \
                        --verbosity=info \
                        --cache=${USE_CACHE} ${REPRODUCIBLE} \
                        --cache-repo=${NXRM_PATH}/cache \
                        --destination=${imageName} \
                        --no-push \
                        --tarPath=/image.tar \
                        --cleanup \
                        --label gitCommitHash=${GIT_COMMIT_HASH} \
                    && mkdir -p /workspace \
                    && rm /kaniko/.docker/* # https://github.com/GoogleContainerTools/kaniko/issues/1232
                '''
            } catch (err) {
                message = "BUILD FAILURE for ${imageName}: " + err.getMessage()
                catchError(message: message, buildResult: 'FAILURE', stageResult: 'FAILURE') {
                    echo message
                }
                currentBuild.result = 'FAILURE'
                throw err
            }
        }

        // Your existing 'Check twistcli version' stage code here

        // Your existing 'Scan with Twistcli' stage code here
        sh "./twistcli images scan --address ${tlConsole} -u ${TL_USER} -p ${TL_PASS} --details ${imageName}"
    }
}
==============================
def buildImages(globalJsonSettings = '{}') {
    // Your existing buildImages logic goes here...

    // Perform Prisma Cloud Image Scan
    try {
        withCredentials([file(credentialsId: 'prismaCloudImageScan.enc.yaml', variable: 'PRISMA_CLOUD_CREDENTIALS')]) {
            // Use the 'prismaCloudScan' step provided by the Prisma Cloud plugin
            // Replace 'IMAGE_NAME' and 'IMAGE_TAG' with your actual image name and tag
            prismaCloudScan credentialsId: 'PRISMA_CLOUD_CREDENTIALS', imageName: 'IMAGE_NAME', imageTag: 'IMAGE_TAG'
        }
    } catch (err) {
        message = "Prisma Cloud Image Scan failed: " + err.getMessage()
        catchError(message: message, buildResult: 'FAILURE', stageResult: 'FAILURE') {
            echo message
        }
        currentBuild.result = 'FAILURE'
        throw err
    }
}
stage('Scan with Twistcli') {
                  sh "./twistcli images scan --address https://$TL_CONSOLE -u $TL_USER -p $TL_PASS --details ${imageName}"




======================================
def scanImage(String imageName) {
    try {
        // Perform Prisma Cloud Image Scan using the Prisma Cloud plugin
        withCredentials([file(credentialsId: 'prismaCloudImageScan.enc.yaml', variable: 'PRISMA_CLOUD_CREDENTIALS')]) {
            prismaCloudScan credentialsId: 'PRISMA_CLOUD_CREDENTIALS', imageName: imageName, imageTag: 'latest'
        }
    } catch (err) {
        message = "Prisma Cloud Image Scan failed: " + err.getMessage()
        catchError(message: message, buildResult: 'FAILURE', stageResult: 'FAILURE') {
            echo message
        }
        currentBuild.result = 'FAILURE'
        throw err
    }
}


==================
try {
    withCredentials([file(credentialsId: 'prismaCloudImageScan.enc.yaml', variable: 'PRISMA_CLOUD_CREDENTIALS')]) {
        // Scan the Docker image using Prisma Cloud
        prismaCloudScan credentialsId: 'PRISMA_CLOUD_CREDENTIALS', imageName: imageName, imageTag: 'latest'
    }
} catch (err) {
    // If the Prisma Cloud scan fails, handle the error
    echo "Prisma Cloud Image Scan failed: " + err.getMessage()
}
