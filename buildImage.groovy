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
    env.NXRM_PATH = "image-nxrm.greyops.touchnet.com"
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
      withCredentials([file(credentialsId: 'pid-gousgnag-tnet-devops.json', variable: 'GOOGLE_APPLICATION_CREDENTIALS')]) {
        lookingFor = 'us.gcr.io/pid-gousgnag-tnet-devops/' + env.IMAGE_NAME + ':' + env.IMAGE_TAG
        println('Checking to make sure ' + lookingFor + ' does not exist.')
        def res = commonUtils.shCmdStatus('gcrane ls us.gcr.io/pid-gousgnag-tnet-devops/' + env.IMAGE_NAME )
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
            && rm /kaniko/.docker/* # https://github.com/GoogleContainerTools/kaniko/issues/1586
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
    withCredentials([file(credentialsId: 'pid-gousgnag-tnet-devops.json', variable: 'GOOGLE_APPLICATION_CREDENTIALS')]) {
      pushImages.pushToGCP("devops", imageJsonSettings)
    }
    if ( getValue(imageJsonSettings, 'pushToGCP') == 'true'  ) {
      withCredentials([file(credentialsId: 'pid-gousgnab-tnet-sandbox1.json', variable: 'GOOGLE_APPLICATION_CREDENTIALS')]) {
        pushImages.pushToGCP("sandbox", imageJsonSettings)
      }
      withCredentials([file(credentialsId: 'pid-gousgnad-tnet-ucomm.json', variable: 'GOOGLE_APPLICATION_CREDENTIALS')]) {
        pushImages.pushToGCP("ncdeDev", imageJsonSettings)
      }
      if ( isDevelopBranch(env.BRANCH_NAME) || isReleaseBranch(env.BRANCH_NAME) ) {
        withCredentials([file(credentialsId: 'pid-gousgnaq-tnet-ucomm.json', variable: 'GOOGLE_APPLICATION_CREDENTIALS')]) {
          pushImages.pushToGCP("ncdeQA", imageJsonSettings)
        }
      }
      if ( isReleaseBranch(env.BRANCH_NAME) && !IMAGE_TAG.contains('SNAPSHOT') ) {
        withCredentials([file(credentialsId: 'pid-gousgnas-tnet-ucomm.json', variable: 'GOOGLE_APPLICATION_CREDENTIALS')]) {
          pushImages.pushToGCP("ncdeCert", imageJsonSettings)
        }

        //ToDo: develop pipeline to promote images from Cert to Prod, remove this push/tag
        withCredentials([file(credentialsId: 'pid-gousgnag-tnet-ucomm.json', variable: 'GOOGLE_APPLICATION_CREDENTIALS')]) {
          pushImages.pushToGCP("ncdeProd", imageJsonSettings)
        }
        sh '''
          rm -v /image.tar
        '''
      }
    }
  }
}
