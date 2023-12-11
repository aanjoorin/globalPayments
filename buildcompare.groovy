def call(globalJsonSettings = '{}') {
  // Setting useCache to false by default: DEVOPS-2588
  def defaultJsonSettings = '''{
    "useCache": "false",
    "pushToGCP": "false",
    "reproducibleImage": "false",
    "dockerfileGlob": "**/*Dockerfile",
    "dockerfileExcludes": "**/dev.Dockerfile,**/node_modules/**",
    "useLatestTag": "false",
    "useAdditionalTags": "",
    "imageName": "",
    "imageDirectory": ""
  }'''

  // Merge the default settings with the global settings
  def imageJsonSettings = mergeJson(defaultJsonSettings, globalJsonSettings)

  // Print the merged settings for visibility
  echo "Defaults: ${defaultJsonSettings} \nOverrides: ${globalJsonSettings} \nSettings: ${imageJsonSettings}"

  // Find Dockerfiles based on the specified glob pattern and excludes
  def foundFiles = findFiles(glob: getValue(imageJsonSettings, 'dockerfileGlob'), excludes: getValue(imageJsonSettings, 'dockerfileExcludes'))

  // Add the path for Kaniko and Busybox to the PATH environment variable
  env.PATH = '/busybox:/kaniko:' + env.PATH

  // Loop through the found Dockerfiles
  foundFiles.each { file ->
    env.NXRM_PATH = "i-nxrm.net.com"
    println('---\nPath: ' + file.path + '\nName: ' + file.name + '\nBranch Name: ' + env.BRANCH_NAME)
    env.DOCKERFILE = file.path

    // Allow for image overrides
    if (getValue(imageJsonSettings, 'imageName') == "") {
      if (file.path == file.name) {
        env.DIRNAME = ''
      } else {
        env.DIRNAME = '/' + file.path.substring(0, file.path.length() - file.name.length() - 1)
      }
    } else {
      env.DIRNAME = '/' + getValue(imageJsonSettings, 'imageName')
    }

    // Allow for image overrides
    if (getValue(imageJsonSettings, 'imageDirectory') == "") {
      env.IMAGE_NAME = getRepo().toLowerCase() + env.DIRNAME.toLowerCase()
    } else {
      env.IMAGE_NAME = (getValue(imageJsonSettings, 'imageDirectory')).toLowerCase() + env.DIRNAME.toLowerCase()
    }
    echo "Image to be generated: ${env.IMAGE_NAME}"

    // Validate the image name for allowed characters
    def allowedImageNameRunes = 'abcdefghijklmnopqrstuvwxyz0123456789_-./'
    if (env.IMAGE_NAME != stringUtils.replaceDisallowedRunes(env.IMAGE_NAME, allowedImageNameRunes)) {
      def err = 'Image Name contains characters that are not allowed.\nImage Name: ' + env.IMAGE_NAME + '\nAllowed Characters: ' + allowedImageNameRunes
      println(err)
      currentBuild.result = 'ABORTED'
      error(err)
    }

    // Set the tag prefix based on the branch name
    if (isReleaseBranch(env.BRANCH_NAME)) {
      env.TAG_PREFIX = "RELEASE-"
    } else {
      env.TAG_PREFIX = env.BRANCH_NAME.replaceAll('/', '_') + '-'
    }

    // Determine if the image build should be reproducible
    if (getValue(imageJsonSettings, 'reproducibleImage') == 'true') {
      env.REPRODUCIBLE = '--reproducible'
    } else {
      env.REPRODUCIBLE = ''
    }

    // Determine the tag suffix based on the Dockerfile name
    if (file.name == 'Dockerfile') {
      env.TAG_SUFFIX = ''
    } else {
      env.TAG_SUFFIX = '-' + file.name.substring(0, file.name.length() - 11)
    }

    // Determine if caching is enabled for the build
    if (getValue(imageJsonSettings, 'useCache') == 'true') {
      env.USE_CACHE = 'true'
    } else {
      env.USE_CACHE = 'false'
    }

    // Validate the image tag for allowed characters
    def allowedImageTagRunes = 'abcdefghijklmnopqrstuvwxyz0123456789_-.ABCDEFGHIJKLMNOPQRSTUVWXYZ'
    env.IMAGE_TAG = env.TAG_PREFIX + env.VERSION + env.TAG_SUFFIX
    if (env.IMAGE_TAG != stringUtils.replaceDisallowedRunes(env.IMAGE_TAG, allowedImageTagRunes)) {
      def err = 'Image Tag contains characters that are not allowed.\nImage Tag: ' + env.IMAGE_TAG + '\nAllowed Characters: ' + allowedImageTagRunes
      println(err)
      currentBuild.result = 'ABORTED'
      error(err)
    }

    // Check if image already exists on GCR for release branches
    if (isReleaseBranch(env.BRANCH_NAME)) {
      withCredentials([file(credentialsId: 'pid-ops.json', variable: 'GOOGLE_APPLICATION_CREDENTIALS')]) {
        def lookingFor = 'us.gcr.io/pid-ops/' + env.IMAGE_NAME + ':' + env.IMAGE_TAG
        println('Checking to make sure ' + lookingFor + ' does not exist.')
        def res = commonUtils.shCmdStatus('gcrane ls us.gcr.io/pid-ops/' + env.IMAGE_NAME)
        if (res['status'] != 0) {
          error('Unable to retrieve list of images')
        }
        res['stdout'].split('\n').each {
          if (it.trim() == lookingFor) {
            def err = 'Image ' + lookingFor + ' exists'
            println(err)
            error(err)
          }
        }
      }
    }

    // Build the Docker image using Kaniko
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

    def call(globalJsonSettings = '{}') {
  // The existing code goes here...

  withCredentials([file(credentialsId: 'prismaCloudImageScan.enc.yaml', variable: 'PRISMA_CLOUD_CREDENTIALS')]) {
    try {
      // Prisma Cloud Image Scan
      echo "Performing Prisma Cloud Image Scan..."
      sh '''
        # Install necessary tools for Prisma Cloud scanning (if required)
        # Replace the following commands with the actual commands required by Prisma Cloud scanning tool (if needed).
        # Example: Install the Prisma Cloud plugin if it's required for scanning
        # Replace 'IMAGE_NAME' and 'IMAGE_TAG' with your actual image name and tag
        # prisma_cloud_plugin_cli scan IMAGE_NAME:IMAGE_TAG
      '''
      echo "Prisma Cloud Image Scan completed successfully."
    } catch (err) {
      message = "Prisma Cloud Image Scan failed: " + err.getMessage()
      catchError(message: message, buildResult: 'FAILURE', stageResult: 'FAILURE') {
        echo message
      }
      currentBuild.result = 'FAILURE'
      throw err
    }
  }



}


    // Push images to GCP (Google Container Registry)
    withCredentials([file(credentialsId: 'pid-ops.json', variable: 'GOOGLE_APPLICATION_CREDENTIALS')]) {
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

        //ToDo: develop pipeline to promote images from Cert to Prod, remove this push/tag
        withCredentials([file(credentialsId: 'pid-prod.json', variable: 'GOOGLE_APPLICATION_CREDENTIALS')]) {
          pushImages.pushToGCP("ncdeProd", imageJsonSettings)
        }
        sh '''
          rm -v /image.tar
        '''
      }
    }
  }
}
 fix this script