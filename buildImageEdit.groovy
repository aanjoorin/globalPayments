def call(globalJsonSettings = '{}') {
  // setting useCache to false by default: DEVOPS-2588
  defaultJsonSettings = '''{
    "useCache":"false",
    "pushToGCP":"false",
    "reproducibleImage":"false",
    "dockerfileGlob":"**/*Dockerfile",
    "dockerfileExcludes":"**/dev.Dockerfile,**/node_modules/**",
    "useLatestTag":"false",
    "useAdditionalTags":"",
    "imageName":"",
    "imageDirectory":"",
    }'''
  imageJsonSettings = mergeJson(defaultJsonSettings, globalJsonSettings)
  echo "Defaults: ${defaultJsonSettings} \nOverrides: ${globalJsonSettings} \nSettings: ${imageJsonSettings}"
  final foundFiles = findFiles(glob: getValue(imageJsonSettings, 'dockerfileGlob'), excludes: getValue(imageJsonSettings, 'dockerfileExcludes') )
  env.PATH = '/busybox:/kaniko:' + env.PATH
  foundFiles.each {
    env.NXRM_PATH = "i-nxrm.net.com"
    println('---\nPath: ' + it.path + '\nName: ' +  it.name + '\nBranch Name: ' + env.BRANCH_NAME )
    env.DOCKERFILE = it.path

    // Allow for image overrides
    if (getValue(imageJsonSettings, 'imageName') == "") {
      if ( it.path == it.name ) {
        env.DIRNAME = ''
      } else {
        env.DIRNAME = '/' + it.path.substring(0, it.path.length() - it.name.length() - 1)
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

    allowedImageNameRunes = 'abcdefghijklmnopqrstuvwxyz0123456789_-./'
    if ( env.IMAGE_NAME != stringUtils.replaceDisallowedRunes(env.IMAGE_NAME, allowedImageNameRunes) ) {
      err = 'Image Name contains characters that are not allowed.\nImage Name: ' + env.IMAGE_NAME + '\nAllowed Characters: ' + allowedImageNameRunes
      println(err)
      currentBuild.result = 'ABORTED'
      error(err)
    }
    if ( isReleaseBranch(env.BRANCH_NAME) ) {
      env.TAG_PREFIX = "RELEASE-"
    } else {
      env.TAG_PREFIX = env.BRANCH_NAME.replaceAll('/','_') + '-'
    }
    if ( getValue(imageJsonSettings, 'reproducibleImage') == 'true' ) {
      env.REPRODUCIBLE = '--reproducible'
    } else {
      env.REPRODUCIBLE = ''
    }
    if ( it.name == 'Dockerfile' ) {
      env.TAG_SUFFIX = ''
    } else {
      env.TAG_SUFFIX = '-' + it.name.substring(0, it.name.length() - 11)
    }
    if ( getValue(imageJsonSettings, 'useCache') == 'true' ) {
      env.USE_CACHE= 'true'
    } else {
      env.USE_CACHE= 'false'
    }
    allowedImageTagRunes = 'abcdefghijklmnopqrstuvwxyz0123456789_-.ABCDEFGHIJKLMNOPQRSTUVWXYZ'
    env.IMAGE_TAG = env.TAG_PREFIX + env.VERSION + env.TAG_SUFFIX
    if ( env.IMAGE_TAG != stringUtils.replaceDisallowedRunes(env.IMAGE_TAG, allowedImageTagRunes ) ) {
      err = 'Image Tag contains characters that are not allowed.\nImage Tag: ' + env.IMAGE_TAG +'\nAllowed Characters: ' + allowedImageTagRunes
      println(err)
      currentBuild.result = 'ABORTED'
      error(err)
    }
    if ( isReleaseBranch(env.BRANCH_NAME) ) {
      withCredentials([file(credentialsId: 'pid-ops.json', variable: 'GOOGLE_APPLICATION_CREDENTIALS')]) {
        lookingFor = 'us.gcr.io/pid-ops/' + env.IMAGE_NAME + ':' + env.IMAGE_TAG
        println('Checking to make sure ' + lookingFor + ' does not exist.')
        def res = commonUtils.shCmdStatus('gcrane ls us.gcr.io/pid-ops/' + env.IMAGE_NAME )
        if ( res['status'] != 0 ) {
          error('Unable to retrieve list of images')
        }
        res['stdout'].split('\n').each {
          if ( it.trim() == lookingFor ) {
            err = 'Image ' + lookingFor + ' exists'
            println(err)
            error(err)
          }
        }
      }
    }

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
    withCredentials([file(credentialsId: 'pid-ops.json', variable: 'GOOGLE_APPLICATION_CREDENTIALS')]) {
      pushImages.pushToGCP("devops", imageJsonSettings)
    }
    if ( getValue(imageJsonSettings, 'pushToGCP') == 'true'  ) {
      withCredentials([file(credentialsId: 'pid-sbx.json', variable: 'GOOGLE_APPLICATION_CREDENTIALS')]) {
        pushImages.pushToGCP("sandbox", imageJsonSettings)
      }
      withCredentials([file(credentialsId: 'pid-dev.json', variable: 'GOOGLE_APPLICATION_CREDENTIALS')]) {
        pushImages.pushToGCP("ncdeDev", imageJsonSettings)
      }
      if ( isDevelopBranch(env.BRANCH_NAME) || isReleaseBranch(env.BRANCH_NAME) ) {
        withCredentials([file(credentialsId: 'pid-qa.json', variable: 'GOOGLE_APPLICATION_CREDENTIALS')]) {
          pushImages.pushToGCP("ncdeQA", imageJsonSettings)
        }
      }
      if ( isReleaseBranch(env.BRANCH_NAME) && !IMAGE_TAG.contains('SNAPSHOT') ) {
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

This script is for building images and pushing them to GCP but it is called in other pipeline scripts like this 

    stage('Build & Push Image') {
      steps {
        container(name: 'kaniko', shell: '/busybox/sh') {
          script {
            buildImages(globalJsonSettings)
          }
        }
      }
    }
    
i have prisma cloud plugins v30.01 installed on my jenkins instance
I added credentials for prisma cloud access key and secret key configure to jenkins under manage jenkins to credentials
to system to Global crendentials (unrestricted) and i gave it an ID of prisma-cloud-plugin-credentialsId
I added the url for prisma cloud UI and access key and secret key configure to jenkins under manage jenkins to configure system under the 
prisma block and tested the connection, it says ok
i want to write a full detailed groovy scipt pipeline including a stage from prisma cloud to scan the docker images after built 
by kaniko for vulnerabilities and it finds any it should fail and not push to GCP else pass and push to GCP
Loop through the list of images and tags built by kaniko and trigger Prisma Cloud scan for each.