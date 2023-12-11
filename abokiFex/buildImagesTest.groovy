def call(globalJsonSettings = '{}') {
  defaultJsonSettings = '{"useCache":"false","pushToGCP":"false","reproducibleImage":"false","dockerfileGlob":"**/*Dockerfile","dockerfileExcludes":"**/dev.Dockerfile,**/node_modules/**","imageName":"${imageName},"imageLocation":"${imageLocation}"}'
  def imageJsonSettings = mergeJson(defaultJsonSettings, globalJsonSettings)
  //imageJsonSettings = mergeJson(defaultJsonSettings, globalJsonSettings)
  def imageName = params.imageName ?: getValue(imageJsonSettings, 'imageName')
  def imageLocation = params.imageLocation ?: getValue(imageJsonSettings, 'imageLocation')
  echo "Defaults: ${defaultJsonSettings} \nOverrides: ${globalJsonSettings} \nSettings: ${imageJsonSettings}"
  final foundFiles = findFiles(glob: getValue(imageJsonSettings, 'dockerfileGlob'), excludes: getValue(imageJsonSettings, 'dockerfileExcludes') )
  env.PATH = '/busybox:/kaniko:' + env.PATH
  foundFiles.each {
    println('---\nPath: ' + it.path + '\nName: ' + it.name + '\nBranch Name: ' + env.BRANCH_NAME )
    env.DOCKERFILE = it.path
    if ( it.path == it.name ) {
      env.DIRNAME = ''
    } else {
      env.DIRNAME = '/' + it.path.substring(0, it.path.length() - it.name.length() - 1)
    }
    env.IMAGE_NAME = getRepo().toLowerCase() + env.DIRNAME.toLowerCase()
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
      withCredentials([file(credentialsId: 'net-sand.json', variable: 'GOOGLE_APPLICATION_CREDENTIALS')]) {
        lookingFor = 'us.gcr.io/net-sand/' + env.IMAGE_NAME + ':' + env.IMAGE_TAG
        println('Checking to make sure ' + lookingFor + ' does not exist.')
        def res = commonUtils.shCmdStatus('gcrane ls us.gcr.io/net-sand/' + env.IMAGE_NAME )
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
              --cache-repo=image-nxrm.ops.net.com/cache \
              --destination=image \
              --no-push \
              --tarPath=/image.tar \
              --cleanup \
              --label gitCommitHash=${GIT_COMMIT_HASH} \
              && mkdir -p /workspace \
              && rm /kaniko/.docker/* # https://github.com/GoogleContainerTools/kaniko/issues/19
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
          withCredentials([file(credentialsId: 'net-sand.json', variable: 'GOOGLE_APPLICATION_CREDENTIALS')]) {
            sh '''
              gcrane push /image.tar us.gcr.io/net-sand/${IMAGE_NAME}:${IMAGE_TAG}
              gcrane tag us.gcr.io/net-sand/${IMAGE_NAME}:${IMAGE_TAG} ${IMAGE_TAG}-${GIT_COMMIT_HASH}
            '''
            println('\n\n\n use the following commad to pull this image. \n   docker image pull image-nxrm.ops.net.com/net-sand/' + env.IMAGE_NAME + ':' + env.IMAGE_TAG + '\n\n or\n\n    docker image pull image-nxrm.ops.net.com/net-sand/' + env.IMAGE_NAME + ':' + env.IMAGE_TAG + '-' + env.GIT_COMMIT_HASH + '\n\n\n')
          }
          if ( getValue(imageJsonSettings, 'pushToGCP') == 'true' ) {
            withCredentials([file(credentialsId: 'dbox1.json', variable: 'GOOGLE_APPLICATION_CREDENTIALS')]) {
              println('Pushing to Sandbox: dbox1')
              sh '''
                gcrane push /image.tar us.gcr.io/dbox1/${IMAGE_NAME}:${IMAGE_TAG}
                gcrane tag us.gcr.io/dbox1/${IMAGE_NAME}:${IMAGE_TAG} ${IMAGE_TAG}-${GIT_COMMIT_HASH}
              '''
            }
            withCredentials([file(credentialsId: 'come.json', variable: 'GOOGLE_APPLICATION_CREDENTIALS')]) {
              println('Pushing to Develop: come')
              sh '''
                gcrane push /image.tar us.gcr.io/come/${IMAGE_NAME}:${IMAGE_TAG}
                gcrane tag us.gcr.io/come/${IMAGE_NAME}:${IMAGE_TAG} ${IMAGE_TAG}-${GIT_COMMIT_HASH}
              '''
            }
            if ( isDevelopBranch(env.BRANCH_NAME) || isReleaseBranch(env.BRANCH_NAME) ) {
              withCredentials([file(credentialsId: 'pid-gousgnaq-tnet-ucomm.json', variable: 'GOOGLE_APPLICATION_CREDENTIALS')]) {
                println('Pushing to QA: pid-gousgnaq-tnet-ucomm')
                sh '''
                  gcrane push /image.tar us.gcr.io/pid-gousgnaq-tnet-ucomm/${IMAGE_NAME}:${IMAGE_TAG}
                  gcrane tag us.gcr.io/pid-gousgnaq-tnet-ucomm/${IMAGE_NAME}:${IMAGE_TAG} ${IMAGE_TAG}-${GIT_COMMIT_HASH}
                '''
              }
            }
            if ( isReleaseBranch(env.BRANCH_NAME) && !IMAGE_TAG.contains('SNAPSHOT') ) {
              withCredentials([file(credentialsId: 'pid-gousgnas-tnet-ucomm.json', variable: 'GOOGLE_APPLICATION_CREDENTIALS')]) {
                println('Pushing to Cert: pid-gousgnas-tnet-ucomm')
                sh '''
                  gcrane push /image.tar us.gcr.io/pid-gousgnas-tnet-ucomm/${IMAGE_NAME}:${IMAGE_TAG}
                  gcrane tag us.gcr.io/pid-gousgnas-tnet-ucomm/${IMAGE_NAME}:${IMAGE_TAG} ${IMAGE_TAG}-${GIT_COMMIT_HASH}
                '''
              }
              //ToDo: develop pipeline to promote images from Cert to Prod, remove this push/tag
              withCredentials([file(credentialsId: 'net-ucomm.json', variable: 'GOOGLE_APPLICATION_CREDENTIALS')]) {
              println('Pushing to Production: net-ucomm')
                sh '''
                  gcrane push /image.tar us.gcr.io/net-ucomm/${IMAGE_NAME}:${IMAGE_TAG}
                  gcrane tag us.gcr.io/net-ucomm/${IMAGE_NAME}:${IMAGE_TAG} ${IMAGE_TAG}-${GIT_COMMIT_HASH}
                '''
              }
              sh '''
                rm -v /image.tar
              '''
            }
          }
        }
      }